/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata

import java.sql.{CallableStatement, Connection}
import java.util.{Timer, TimerTask}

import com.gemstone.gemfire.internal.ByteArrayDataInput
import com.gemstone.gemfire.{CancelException, DataSerializer}
import com.pivotal.gemfirexd.Attribute
import com.pivotal.gemfirexd.internal.engine.ui.{SnappyExternalTableStats, SnappyIndexStats, SnappyRegionStats}
import io.snappydata.Constant._

import org.apache.spark.SparkContext
import org.apache.spark.sql.execution.datasources.jdbc.{JDBCOptions, JdbcUtils}
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object SnappyThinConnectorTableStatsProvider extends TableStatsProviderService {

  private var conn: Connection = null
  private var getStatsStmt: CallableStatement = null
  private var _url: String = null

  def initializeConnection(context: Option[SparkContext] = None): Unit = {
    var securePart = ""
    context match {
      case Some(sc) =>
        val user = sc.getConf.get(Constant.SPARK_STORE_PREFIX + Attribute.USERNAME_ATTR, "")
        if (!user.isEmpty) {
          val pass = sc.getConf.get(Constant.SPARK_STORE_PREFIX + Attribute.PASSWORD_ATTR, "")
          securePart = s";user=$user;password=$pass"
        }
      case None =>
    }
    val jdbcOptions = new JDBCOptions(_url + securePart + ";route-query=false;", "",
      Map("driver" -> Constant.JDBC_CLIENT_DRIVER))
    conn = JdbcUtils.createConnectionFactory(jdbcOptions)()
    getStatsStmt = conn.prepareCall("call sys.GET_SNAPPY_TABLE_STATS(?)")
    getStatsStmt.registerOutParameter(1, java.sql.Types.BLOB)
  }

  def start(sc: SparkContext): Unit = {
    throw new IllegalStateException("This is expected to be called for " +
        "Embedded cluster mode only")
  }

  def start(sc: SparkContext, url: String): Unit = {
    if (!doRun) {
      this.synchronized {
        if (!doRun) {
          _url = url
          initializeConnection(Some(sc))
          val delay = sc.getConf.getLong(Constant.SPARK_SNAPPY_PREFIX +
              "calcTableSizeInterval", DEFAULT_CALC_TABLE_SIZE_SERVICE_INTERVAL)
          doRun = true
          new Timer("SnappyThinConnectorTableStatsProvider", true).schedule(
            new TimerTask {
              override def run(): Unit = {
                try {
                  if (doRun) {
                    aggregateStats()
                  }
                } catch {
                  case _: CancelException => // ignore
                  case e: Exception => logError("SnappyThinConnectorTableStatsProvider", e)
                }
              }
            }, delay, delay)
        }
      }
    }
  }

  def executeStatsStmt(sc: Option[SparkContext] = None): Unit = {
    if (conn == null) initializeConnection(sc)
    getStatsStmt.execute()
  }

  private def closeConnection(): Unit = {
    val c = this.conn
    if (c ne null) {
      try {
        c.close()
      } catch {
        case NonFatal(_) => // ignore
      }
      conn = null
    }
  }

  override def getStatsFromAllServers(sc: Option[SparkContext] = None): (Seq[SnappyRegionStats],
      Seq[SnappyIndexStats], Seq[SnappyExternalTableStats]) = {
    try {
      executeStatsStmt(sc)
    } catch {
      case e: Exception =>
        logWarning("Warning: unable to retrieve table stats " +
            "from SnappyData cluster due to " + e.toString)
        logDebug("Exception stack trace: ", e)
        closeConnection()
        return (Seq.empty[SnappyRegionStats], Seq.empty[SnappyIndexStats],
            Seq.empty[SnappyExternalTableStats])
    }
    val value = getStatsStmt.getBlob(1)
    val bdi: ByteArrayDataInput = new ByteArrayDataInput
    bdi.initialize(value.getBytes(1, value.length().asInstanceOf[Int]), null)
    val regionStats: java.util.List[SnappyRegionStats] =
    DataSerializer.readObject(bdi).asInstanceOf[java.util.ArrayList[SnappyRegionStats]]
    (regionStats.asScala, Seq.empty[SnappyIndexStats], Seq.empty[SnappyExternalTableStats])
  }

  override def stop(): Unit = {
    super.stop()
    closeConnection()
  }
}
