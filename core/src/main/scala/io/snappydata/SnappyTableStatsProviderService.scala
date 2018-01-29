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

import java.util.function.BiFunction

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.implicitConversions
import scala.util.control.NonFatal

import com.gemstone.gemfire.CancelException
import com.gemstone.gemfire.cache.execute.FunctionService
import com.gemstone.gemfire.i18n.LogWriterI18n
import com.gemstone.gemfire.internal.SystemTimer
import com.gemstone.gemfire.internal.cache.{AbstractRegionEntry, ExternalTableMetaData, LocalRegion, PartitionedRegion, RegionEntry}
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector.ListResultCollectorValue
import com.pivotal.gemfirexd.internal.engine.distributed.{GfxdListResultCollector, GfxdMessage}
import com.pivotal.gemfirexd.internal.engine.sql.execute.MemberStatisticsMessage
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer
import com.pivotal.gemfirexd.internal.engine.ui._
import io.snappydata.Constant._

import org.apache.spark.SparkContext
import org.apache.spark.sql.execution.columnar.impl.{ColumnFormatKey, ColumnFormatRelation, ColumnFormatValue, RemoteEntriesIterator}
import org.apache.spark.sql.{SnappyContext, ThinClientConnectorMode}

/*
* Object that encapsulates the actual stats provider service. Stats provider service
* will either be SnappyEmbeddedTableStatsProviderService or SnappyThinConnectorTableStatsProvider
 */
object SnappyTableStatsProviderService {
  // var that points to the actual stats provider service
  private var statsProviderService: TableStatsProviderService = _

  def start(sc: SparkContext): Unit = {
    SnappyContext.getClusterMode(sc) match {
      case ThinClientConnectorMode(_, _) =>
        throw new IllegalStateException(
          "Not expected to be called for ThinClientConnectorMode")
      case _ =>
        statsProviderService = SnappyEmbeddedTableStatsProviderService
    }
    statsProviderService.start(sc)
  }

  def start(sc: SparkContext, url: String): Unit = {
    SnappyContext.getClusterMode(sc) match {
      case ThinClientConnectorMode(_, _) =>
        statsProviderService = SnappyThinConnectorTableStatsProvider
      case _ =>
        throw new IllegalStateException(
          "This is expected to be called for ThinClientConnectorMode only")
    }
    statsProviderService.start(sc, url)
  }

  def stop(): Unit = {
    val service = statsProviderService
    if (service ne null) {
      service.stop()
    }
  }

  def getService: TableStatsProviderService = {
    val service = statsProviderService
    if (service eq null) {
      throw new IllegalStateException("SnappyTableStatsProviderService not started")
    }
    service
  }

  var suspendCacheInvalidation = false
}

object SnappyEmbeddedTableStatsProviderService extends TableStatsProviderService {

  def start(sc: SparkContext): Unit = {
    if (!doRun) {
      this.synchronized {
        if (!doRun) {
          val delay = sc.getConf.getLong(SPARK_SNAPPY_PREFIX +
              "calcTableSizeInterval", DEFAULT_CALC_TABLE_SIZE_SERVICE_INTERVAL)
          doRun = true
          Misc.getGemFireCache.getCCPTimer.schedule(
            new SystemTimer.SystemTimerTask {
              private val logger: LogWriterI18n = Misc.getGemFireCache.getLoggerI18n

              override def run2(): Unit = {
                try {
                  if (doRun) {
                    aggregateStats()
                  }
                } catch {
                  case _: CancelException => // ignore
                  case e: Exception => if (e.getMessage != null && !e.getMessage.contains(
                    "com.gemstone.gemfire.cache.CacheClosedException")) {
                    logger.warning(e)
                  } else {
                    logger.error(e)
                  }
                }
              }

              override def getLoggerI18n: LogWriterI18n = {
                logger
              }
            },
            delay, delay)
        }
      }
    }
  }

  override def start(sc: SparkContext, url: String): Unit = {
    throw new IllegalStateException("This is expected to be called for " +
        "ThinClientConnectorMode only")
  }

  override def fillAggregatedMemberStatsOnDemand(): Unit = {

    try {
      val existingMembers = membersInfo.keys.toArray
      val collector = new GfxdListResultCollector(null, true)
      val msg = new MemberStatisticsMessage(collector)

      msg.executeFunction()

      val memStats = collector.getResult

      val itr = memStats.iterator()

      val members = mutable.Map.empty[String, mutable.Map[String, Any]]
      while (itr.hasNext) {
        val o = itr.next().asInstanceOf[ListResultCollectorValue]
        val memMap = o.resultOfSingleExecution.asInstanceOf[java.util.HashMap[String, Any]]
        val map = mutable.HashMap.empty[String, Any]
        val keyItr = memMap.keySet().iterator()

        while (keyItr.hasNext) {
          val key = keyItr.next()
          map.put(key, memMap.get(key))
        }
        map.put("status", "Running")

        val dssUUID = memMap.get("diskStoreUUID").asInstanceOf[java.util.UUID]
        if (dssUUID != null) {
          members.put(dssUUID.toString, map)
        } else {
          members.put(memMap.get("id").asInstanceOf[String], map)
        }
      }
      membersInfo ++= members
      // mark members no longer running as stopped
      existingMembers.filterNot(members.contains).foreach(m =>
        membersInfo(m).put("status", "Stopped"))
    } catch {
      case e: Exception => logWarning(e.getMessage, e)
    }
  }

  override def getStatsFromAllServers(sc: Option[SparkContext] = None): (Seq[SnappyRegionStats],
      Seq[SnappyIndexStats], Seq[SnappyExternalTableStats]) = {
    var result = new java.util.ArrayList[SnappyRegionStatsCollectorResult]().asScala
    var externalTables = scala.collection.mutable.Buffer.empty[SnappyExternalTableStats]
    val dataServers = GfxdMessage.getAllDataStores
    try {
      if (dataServers != null && dataServers.size() > 0) {
        result = FunctionService.onMembers(dataServers)
            .withCollector(new GfxdListResultCollector())
            .execute(SnappyRegionStatsCollectorFunction.ID).getResult().
            asInstanceOf[java.util.ArrayList[SnappyRegionStatsCollectorResult]]
            .asScala
      }

      // External Tables
      val hiveTables: java.util.List[ExternalTableMetaData] =
        Misc.getMemStore.getExternalCatalog.getHiveTables(true)
      externalTables = hiveTables.asScala.collect {
        case table if table.tableType.equalsIgnoreCase("EXTERNAL") =>
          new SnappyExternalTableStats(table.entityName, table.tableType, table.shortProvider,
            table.externalStore, table.dataSourcePath, table.driverClass)
      }
    }
    catch {
      case NonFatal(e) => log.warn(e.getMessage, e)
    }

    (result.flatMap(_.getRegionStats.asScala),
     result.flatMap(_.getIndexStats.asScala),
     externalTables)
  }

  type PRIterator = PartitionedRegion#PRLocalScanIterator

  /**
   * Allows pulling stats rows efficiently if required. For the corner case
   * of bucket moving away while iterating other buckets.
   */
  private val createRemoteIterator = new BiFunction[java.lang.Integer, PRIterator,
      java.util.Iterator[RegionEntry]] {
    override def apply(bucketId: Integer,
        iter: PRIterator): java.util.Iterator[RegionEntry] = {
      new RemoteEntriesIterator(bucketId, Array.emptyIntArray,
        iter.getPartitionedRegion, null)
    }
  }

  def publishColumnTableRowCountStats(): Unit = {
    def asSerializable[C](c: C) = c.asInstanceOf[C with Serializable]

    val regions = asSerializable(Misc.getGemFireCache.getApplicationRegions.asScala)
    for (region: LocalRegion <- regions) {
      if (region.getDataPolicy.withPartitioning()) {
        val table = Misc.getFullTableNameFromRegionPath(region.getFullPath)
        val pr = region.asInstanceOf[PartitionedRegion]
        val container = pr.getUserAttribute.asInstanceOf[GemFireContainer]
        if (ColumnFormatRelation.isColumnTable(table) &&
            pr.getLocalMaxMemory > 0) {
          var numColumnsInTable = -1
          // Resetting PR numRows in cached batch as this will be calculated every time.
          var rowsInColumnBatch = 0L
          var offHeapSize = 0L
          if (container ne null) {
            // TODO: this should use a transactional iterator to get a consistent
            // snapshot (also pass the same transaction to getNumColumnsInTable
            //   for reading value and delete count)
            val itr = new pr.PRLocalScanIterator(true /* primaryOnly */ , null /* no TX */ ,
              null /* not required since includeValues is false */ ,
              createRemoteIterator, false /* forUpdate */ , false /* includeValues */)
            // using direct region operations
            while (itr.hasNext) {
              val re = itr.next().asInstanceOf[AbstractRegionEntry]
              val key = re.getRawKey.asInstanceOf[ColumnFormatKey]
              if (numColumnsInTable < 0) {
                numColumnsInTable = key.getNumColumnsInTable(table)
              }
              rowsInColumnBatch += key.getColumnBatchRowCount(itr, re,
                numColumnsInTable)
              re._getValue() match {
                case v: ColumnFormatValue => offHeapSize += v.getOffHeapSizeInBytes
                case _ =>
              }
            }
          }
          val stats = pr.getPrStats
          stats.setPRNumRowsInColumnBatches(rowsInColumnBatch)
          stats.setOffHeapSizeInBytes(offHeapSize)
        }
      }
    }
  }
}
