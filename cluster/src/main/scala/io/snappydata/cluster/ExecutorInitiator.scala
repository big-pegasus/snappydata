/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package io.snappydata.cluster

import java.net.URL
import java.util

import scala.collection.mutable
import scala.util.control.NonFatal

import com.gemstone.gemfire.CancelException
import com.gemstone.gemfire.distributed.internal.MembershipListener
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils
import io.snappydata.gemxd.ClusterCallbacksImpl

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.executor.SnappyCoarseGrainedExecutorBackend
import org.apache.spark.memory.SnappyUnifiedMemoryManager
import org.apache.spark.sql.SnappyContext
import org.apache.spark.sql.collection.Utils
import org.apache.spark.{Logging, SparkCallbacks, SparkConf, SparkEnv}

/**
 * This class is responsible for initiating the executor process inside
 * the jvm. Also, if an executor has to be stopped, driverURL can be set as None
 * and it will take care of stopping the executor.
 */
object ExecutorInitiator extends Logging {

  val SNAPPY_MEMORY_MANAGER: String = classOf[SnappyUnifiedMemoryManager].getName

  var executorRunnable: ExecutorRunnable = new ExecutorRunnable

  var executorThread: Thread = new Thread(executorRunnable)

  class ExecutorRunnable() extends Runnable {
    private var driverURL: Option[String] = None
    private var driverDM: InternalDistributedMember = _
    @volatile var stopTask = false
    private var retryTask: Boolean = false
    private val lock = new Object()
    private val testLock = new Object()

    val membershipListener = new MembershipListener {
      override def quorumLost(failures: util.Set[InternalDistributedMember],
          remaining: util.List[InternalDistributedMember]): Unit = {}

      override def memberJoined(id: InternalDistributedMember): Unit = {}

      override def memberSuspect(id: InternalDistributedMember,
          whoSuspected: InternalDistributedMember): Unit = {}

      override def memberDeparted(id: InternalDistributedMember, crashed: Boolean): Unit = {
        executorRunnable.memberDeparted(id)
      }
    }

    def memberDeparted(departedDM: InternalDistributedMember): Unit = lock.synchronized {
      if (departedDM.equals(driverDM)) {
        setDriverDetails(None, null)
      }
    }

    def setRetryFlag(retry: Boolean = true): Unit = lock.synchronized {
      retryTask = retry
      lock.notify()
    }

    // Test hook. Not to be used in other situations
    def waitForExecutor(retry: Boolean = true): Unit = testLock.synchronized {
      testLock.wait(30000)
    }

    def getRetryFlag: Boolean = lock.synchronized {
      retryTask
    }

    def getDriverURL: Option[String] = lock.synchronized {
      driverURL
    }

    def setDriverDetails(url: Option[String],
        dm: InternalDistributedMember): Unit = lock.synchronized {
      driverURL = url
      driverDM = dm
      SnappyContext.clearStaticArtifacts()
      lock.notify()
    }

    override def run(): Unit = {
      var prevDriverURL = ""
      var env: SparkEnv = null
      var numTries = 0
      try {
        GemFireXDUtils.getGfxdAdvisor.getDistributionManager
            .addMembershipListener(membershipListener)
        while (!stopTask) {
          try {

            Misc.checkIfCacheClosing(null)
            if (prevDriverURL == getDriverURLString && !getRetryFlag) {
              lock.synchronized {
                while (prevDriverURL == getDriverURLString && !getRetryFlag) {
                  lock.wait(5000)
                }
              }
            } else {
              if (getRetryFlag) {
                if (numTries >= 50) {
                  logError("Exhausted number of retries to connect to the driver. Exiting.")
                  return
                }
                // if it's a retry, wait for sometime before we retry.
                // This is a measure to ensure that some unforeseen circumstance
                // does not lead to continous retries and the thread hogs the CPU.
                numTries += 1
                Thread.sleep(3000)
              }
              // kill if an executor is already running.
              SparkCallbacks.stopExecutor(env)
              env = null

              getDriverURL match {
                case Some(url) =>

                  /**
                   * The executor initialization code has been picked from
                   * CoarseGrainedExecutorBackend.
                   * We need to track the changes there and merge them here on a regular basis.
                   */
                  val myId = GemFireCacheImpl.getExisting.getMyId
                  val executorHost = myId.getHost
                  val memberId = myId.toString
                  SparkHadoopUtil.get.runAsSparkUser { () =>

                    // Fetch the driver's Spark properties.
                    val executorConf = new SparkConf
                    Utils.setDefaultSerializerAndCodec(executorConf)

                    val port = executorConf.getInt("spark.executor.port", 0)
                    val (ioEncryptionKey, props) =
                      SparkCallbacks.fetchDriverProperty(memberId, executorHost,
                      executorConf, port, url)

                    val driverConf = new SparkConf
                    Utils.setDefaultSerializerAndCodec(driverConf)

                    for ((key, value) <- props) {
                      // this is required for SSL in standalone mode
                      if (SparkCallbacks.isExecutorStartupConf(key)) {
                        driverConf.setIfMissing(key, value)
                      } else {
                        driverConf.set(key, value)
                      }
                    }
                    // TODO: Hemant: add executor specific properties from local
                    // TODO: conf to this conf that was received from driver.

                    // If memory manager is not set, use Snappy unified memory manager
                    driverConf.setIfMissing("spark.memory.manager",
                      SNAPPY_MEMORY_MANAGER)

                    val cores = driverConf.getInt("spark.executor.cores",
                      Runtime.getRuntime.availableProcessors() * 2)

                    env = SparkCallbacks.createExecutorEnv(driverConf,
                      memberId, executorHost, port, cores, ioEncryptionKey, isLocal = false)

                    // This is not required with snappy
                    val userClassPath = new mutable.ListBuffer[URL]()

                    val rpcenv = SparkCallbacks.getRpcEnv(env)

                    val executor = new SnappyCoarseGrainedExecutorBackend(
                      rpcenv, url, memberId, executorHost,
                      cores, userClassPath, env)

                    rpcenv.setupEndpoint("Executor", executor)
                  }
                  prevDriverURL = url
                  testLock.synchronized(testLock.notify())
                case None =>
                  // If driver url is none, already running executor is stopped.
                  prevDriverURL = ""
              }
              setRetryFlag(false)
            }
          } catch {
            case e@(NonFatal(_) | _: InterruptedException) =>
              try {
                Misc.checkIfCacheClosing(e)
                // log any exception other than those due to cache closing
                logWarning("Unexpected exception in ExecutorInitiator", e)
              } catch {
                case NonFatal(_) => stopTask = true // just stop the task
              }
          }
        } // end of while(true)
      } catch {
        case e: Throwable =>
          logWarning("ExecutorInitiator failing with exception: ", e)
      } finally {
        // kill if an executor is already running.
        SparkCallbacks.stopExecutor(env)
        try {
          Misc.checkIfCacheClosing(null)
          GemFireXDUtils.getGfxdAdvisor.getDistributionManager
              .removeMembershipListener(membershipListener)
        } catch {
          case _: CancelException => // do nothing
        }
      }
    }

    def getDriverURLString: String = getDriverURL match {
      case Some(x) => x
      case None => ""
    }
  }

  /**
   * This should be called only when the process is terminating.
   * If a process ceases to be an executor, only startOrTransmuteExecutor should be called
   * with None.
   */
  def stop(): Unit = {
    if (executorThread.getState != Thread.State.NEW) {
      executorRunnable.stopTask = true
    }
    executorRunnable.setDriverDetails(None, null)
    Utils.clearDefaultSerializerAndCodec()
  }

  def restartExecutor(): Unit = {
    executorRunnable.setRetryFlag()
  }

  def waitForExecutor(): Unit = {
    executorRunnable.waitForExecutor()
  }

  /**
   * Set the new driver url and start the thread if not already started
   */
  def startOrTransmuteExecutor(driverURL: Option[String],
      driverDM: InternalDistributedMember): Unit = {
    // Avoid creation of executor inside the Gem accessor
    // that is a Spark driver but has joined the gem system
    // in the non embedded mode
    if (SparkCallbacks.isDriver) {
      logInfo("Executor cannot be instantiated in this " +
          "VM as a Spark driver is already running. ")
      return
    }

    if (ServerGroupUtils.isGroupMember(ClusterCallbacksImpl.getLeaderGroup())) {
      logInfo("Executor cannot be instantiated in a lead vm.")
      return
    }

    executorRunnable.setDriverDetails(driverURL, driverDM)
    // start the executor thread if driver URL is set and the thread
    // is not already started.
    driverURL match {
      case Some(_) =>
        if (executorThread.getState == Thread.State.NEW) {
          logInfo("About to start thread " + executorThread.getName)
          executorThread.setDaemon(true)
          executorThread.start()
        } else if (executorThread.getState == Thread.State.TERMINATED) {
          // Restart a thread after it has been stopped
          // This is required for dunit case mainly.
          executorRunnable = new ExecutorRunnable
          executorThread = new Thread(executorRunnable)
          logInfo("Spawning new thread " + executorThread.getName + " and starting")
          executorRunnable.setDriverDetails(driverURL, driverDM)
          executorThread.setDaemon(true)
          executorThread.start()
        }
      case None =>
    }
  }
}
