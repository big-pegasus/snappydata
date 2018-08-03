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
package org.apache.spark.sql.store

import io.snappydata.SnappyFunSuite
import io.snappydata.core.{RefData, TestData2}
import org.scalatest.BeforeAndAfterAll

import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.exchange.Exchange
import org.apache.spark.sql.execution.joins.{HashJoinExec, SortMergeJoinExec}
import org.apache.spark.sql.execution.{PartitionedPhysicalScan, QueryExecution, RowDataSourceScanExec}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.{SaveMode, SnappyContext}

case class TestDatak(key1: Int, value: String, ref: Int)

class SnappyJoinSuite extends SnappyFunSuite with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    snc.conf.clear()
    super.afterAll()
  }

  val props = Map.empty[String, String]

  test("Replicated table join with PR Table") {

    val rdd = sc.parallelize((1 to 5).map(i => RefData(i, s"$i")))
    snc.conf.setConfString("spark.sql.autoBroadcastJoinThreshold", "-1")
    val refDf = snc.createDataFrame(rdd)
    snc.sql("DROP TABLE IF EXISTS RR_TABLE")

    snc.sql("CREATE TABLE RR_TABLE(OrderRef INT NOT NULL, description String)")

    refDf.write.insertInto("RR_TABLE")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE")

    snc.sql("CREATE TABLE PR_TABLE(OrderId INT NOT NULL,description String, " +
        "OrderRef INT) USING row " +
        "options (" +
        "PARTITION_BY 'OrderRef')")

    val dimension = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension)
    dimensionDf.write.format("row").mode(SaveMode.Append).options(props)
        .saveAsTable("PR_TABLE")

    val countDf = snc.sql("select * from PR_TABLE P JOIN RR_TABLE R " +
        "ON P.ORDERREF = R.ORDERREF")

    val qe = new QueryExecution(snc.snappySession, countDf.logicalPlan)
    val plan = qe.executedPlan
    val lj = plan collectFirst {
      case lc: HashJoinExec => lc
    }
    lj.getOrElse(sys.error(s"Can't find Local join in a 1 partitioned relation"))

    val t1 = System.currentTimeMillis()
    assert(countDf.count() === 1000) // Make sure aggregation is working with local join
    val t2 = System.currentTimeMillis()
    logInfo("Time taken = " + (t2 - t1))

    val projectDF = snc.sql("select ORDERID, P.DESCRIPTION, R.DESCRIPTION " +
        "from PR_TABLE P JOIN RR_TABLE R ON P.ORDERREF = R.ORDERREF")
    assert(projectDF.columns.length === 3)

    val sumDF = snc.sql("select SUM(ORDERID)from PR_TABLE P JOIN RR_TABLE R " +
        "ON P.ORDERREF = R.ORDERREF")
    assert(sumDF.collect()(0).getLong(0) === ((1000 * 1001) / 2))
  }

  test("Replicated table join with Replicated Table") {

    val rdd = sc.parallelize((1 to 5).map(i => RefData(i, s"$i")))
    val refDf = snc.createDataFrame(rdd)
    snc.sql("DROP TABLE IF EXISTS RR_TABLE1")

    snc.sql("CREATE TABLE RR_TABLE1(OrderRef INT NOT NULL, " +
        "description String) USING row options()")

    refDf.write.insertInto("RR_TABLE1")

    snc.sql("DROP TABLE IF EXISTS RR_TABLE2")

    snc.sql("CREATE TABLE RR_TABLE2(OrderId INT NOT NULL," +
        "description String, OrderRef INT) USING row options()")

    val dimension = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension)
    dimensionDf.write.insertInto("RR_TABLE2")

    val countDf = snc.sql(
      "select * from RR_TABLE1 P JOIN RR_TABLE2 R ON P.ORDERREF = R.ORDERREF")
    val qe = new QueryExecution(snc.snappySession, countDf.logicalPlan)

    val lj = qe.executedPlan collectFirst {
      case lc: HashJoinExec => lc
    }
    lj.getOrElse(sys.error(s"Can't find Local join in a 1 partitioned relation"))

    val t1 = System.currentTimeMillis()
    assert(countDf.count() === 1000) // Make sure aggregation is working with local join
    val t2 = System.currentTimeMillis()
    logInfo("Time taken = " + (t2 - t1))

    val projectDF = snc.sql("select R.ORDERID, P.DESCRIPTION, R.DESCRIPTION " +
        "from RR_TABLE1 P JOIN RR_TABLE2 R ON P.ORDERREF = R.ORDERREF")
    assert(projectDF.columns.length === 3)

    val sumDF = snc.sql("select SUM(R.ORDERID)from RR_TABLE1 P JOIN " +
        "RR_TABLE2 R ON P.ORDERREF = R.ORDERREF")
    assert(sumDF.collect()(0).getLong(0) === ((1000 * 1001) / 2))

  }

  test("Join multiple replicated tables followed by a partitioned table") {

    val rdd = sc.parallelize((1 to 5).map(i => RefData(i, s"$i")))
    val refDf = snc.createDataFrame(rdd)
    snc.sql("DROP TABLE IF EXISTS RR_TABLE1")

    snc.sql("CREATE TABLE RR_TABLE1(OrderRef INT NOT NULL, " +
        "description String) USING row options()")

    refDf.write.insertInto("RR_TABLE1")

    snc.sql("DROP TABLE IF EXISTS RR_TABLE2")

    snc.sql("CREATE TABLE RR_TABLE2(OrderId INT NOT NULL," +
        "description String, OrderRef INT) USING row options()")

    val dimension = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))
    val dimensionDf = snc.createDataFrame(dimension)
    dimensionDf.write.insertInto("RR_TABLE2")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE")

    snc.sql("CREATE TABLE PR_TABLE(OrderId INT,description String, " +
        "OrderRef INT) USING column " +
        "options (" +
        "PARTITION_BY 'OrderId')")

    val dimension_pr = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))
    val dimensionDf_pr = snc.createDataFrame(dimension_pr)
    dimensionDf_pr.write.insertInto("PR_TABLE")

    val countDf = snc.sql("select * from RR_TABLE1 R1, RR_TABLE2 R2, " +
        "PR_TABLE P where R1.ORDERREF = R2.ORDERREF AND P.OrderId = R2.OrderId")
    val qe = new QueryExecution(snc.snappySession, countDf.logicalPlan)

    qe.executedPlan foreach {
      case SortMergeJoinExec(_, _, _, _, _, _) =>
        throw new Exception("This should have been a local join")
      case _ =>
    }
    assert(countDf.count() === 1000) // Make sure aggregation is working with local join
  }


  test("Check shuffle in operations with partition pruning"){
    val t1 = "t1"
    val t2 = "t2"

    snc.setConf[Long](SQLConf.AUTO_BROADCASTJOIN_THRESHOLD, -1L)
    snc.sql(s"create table $t1 (ol_1_int_id  integer," +
        s" ol_1_int2_id  integer, ol_1_str_id STRING) using column " +
        "options( partition_by 'ol_1_int_id', buckets '16')")
    snc.sql(s"create table $t2 (ol_1_int_id  integer," +
        s" ol_1_int2_id  integer, ol_1_str_id STRING) using column " +
        "options( partition_by 'ol_1_int_id', buckets '16')")

    var df = snc.sql(s"select sum(ol_1_int2_id)  from $t1 where ol_1_int_id=1")
    checkForShuffle(df.logicalPlan, snc , shuffleExpected = false)

    // with limit
    df = snc.sql(s"select sum(ol_1_int2_id)  from $t1 where ol_1_int_id=1 limit 1")
    checkForShuffle(df.logicalPlan, snc , shuffleExpected = false)

    df = snc.sql(s"update $t1 set ol_1_str_id = '3' where ol_1_int_id in (" +
        s"select ol_1_int_id from $t2 where $t2.ol_1_int_id=1)")

    checkForShuffle(df.logicalPlan, snc , shuffleExpected = false)

    snc.dropTable("t1");
    snc.dropTable("t2");

  }

  /**
   * This method is very specific to  PartitionedDataSourceScan and
   * snappy join improvements
   */
  def checkForShuffle(plan: LogicalPlan, snc: SnappyContext,
      shuffleExpected: Boolean): Unit = {

    val qe = new QueryExecution(snc.snappySession, plan)
    // logInfo(qe.executedPlan)
    val lj = qe.executedPlan collect {
      case ex: Exchange => ex
    }
    if (shuffleExpected) {
      if (lj.isEmpty) sys.error(s"Shuffle Expected , but was not found")
    } else {
      lj.foreach(a => a.child.collect {
        // this means no Exhange should have child as PartitionedPhysicalRDD
        case p: PartitionedPhysicalScan => sys.error(
          s"Did not expect exchange with partitioned scan with same partitions")
        case p: RowDataSourceScanExec => sys.error(
          s"Did not expect RowDataSourceScanExec with PartitionedDataSourceScan")
        case _ => // do nothing, may be some other Exchange and not with scan
      })
    }
  }

  test("Row PR table join with PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    snc.conf.setConfString("spark.sql.autoBroadcastJoinThreshold", "-1")
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE1")

    snc.sql("CREATE TABLE PR_TABLE1(OrderId INT NOT NULL,description String, " +
        "OrderRef INT) USING row " +
        "options (" +
        "PARTITION_BY 'OrderId, OrderRef')")

    refDf.write.insertInto("PR_TABLE1")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE2")

    snc.sql("CREATE TABLE PR_TABLE2(OrderId INT NOT NULL,description String, " +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId,OrderRef'," +
        "COLOCATE_WITH 'PR_TABLE1')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.insertInto("PR_TABLE2")

    partitionToPartitionJoinAssertions(snc, "PR_TABLE1", "PR_TABLE2")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE2")
  }

  test("Row PR table join with PR Table without colocation") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE22")

    snc.sql("CREATE TABLE PR_TABLE22(OrderId INT, description String, " +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId,OrderRef')")

    refDf.write.insertInto("PR_TABLE22")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE23")

    snc.sql("CREATE TABLE PR_TABLE23(OrderId INT ,description String, " +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId,OrderRef')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.insertInto("PR_TABLE23")

    val excatJoinKeys = snc.sql(s"select P.OrderRef, P.description from " +
        s"PR_TABLE22 P JOIN PR_TABLE23 R ON P.OrderId = R.OrderId AND " +
        s"P.OrderRef = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, shuffleExpected = false)

    assert(excatJoinKeys.count() === 500)
  }

  test("Column PR table join with PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE3")

    snc.sql("CREATE TABLE PR_TABLE3(OrderId INT, description String, " +
        "OrderRef INT) USING column " +
        "options (" +
        "PARTITION_BY 'OrderId,OrderRef')")

    refDf.write.format("column").mode(SaveMode.Append).options(props)
        .saveAsTable("PR_TABLE3")

    val countdf = snc.sql("select * from PR_TABLE3")
    assert(countdf.count() == 1000)

    snc.sql("DROP TABLE IF EXISTS PR_TABLE4")

    snc.sql("CREATE TABLE PR_TABLE4(OrderId INT ,description String, " +
        "OrderRef INT) USING column options (" +
        "PARTITION_BY 'OrderId,OrderRef'," +
        "COLOCATE_WITH 'PR_TABLE3')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.insertInto("PR_TABLE4")
    val countdf1 = snc.sql("select * from PR_TABLE4")
    assert(countdf1.count() == 1000)
    partitionToPartitionJoinAssertions(snc, "PR_TABLE3", "PR_TABLE4")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE4")
  }

  test("Column PR table join with PR Table without colocation") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE20")

    snc.sql("CREATE TABLE PR_TABLE20(OrderId INT, description String, " +
        "OrderRef INT) USING column options (" +
        "PARTITION_BY 'OrderId,OrderRef')")

    refDf.write.insertInto("PR_TABLE20")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE21")

    snc.sql("CREATE TABLE PR_TABLE21(OrderId INT ,description String, " +
        "OrderRef INT) USING column options (" +
        "PARTITION_BY 'OrderId,OrderRef')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.insertInto("PR_TABLE21")

    val excatJoinKeys = snc.sql(s"select P.OrderRef, P.description from " +
        s"PR_TABLE20 P JOIN PR_TABLE21 R ON P.OrderId = R.OrderId AND " +
        s"P.OrderRef = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, shuffleExpected = false)

    assert(excatJoinKeys.count() === 500)
  }

  test("Column PR table join with Non user mentioned PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE5")

    snc.sql("CREATE TABLE PR_TABLE5(OrderId INT, description String, " +
        "OrderRef INT) USING column options (" +
        "PARTITION_BY 'OrderId, OrderRef')")

    refDf.write.format("column").mode(SaveMode.Append).options(props)
        .saveAsTable("PR_TABLE5")

    val countdf = snc.sql("select * from PR_TABLE5")
    assert(countdf.count() == 1000)

    snc.sql("DROP TABLE IF EXISTS PR_TABLE6")

    snc.sql("CREATE TABLE PR_TABLE6(OrderId INT ,description String, " +
        "OrderRef INT) USING column options()")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.insertInto("PR_TABLE6")
    val countdf1 = snc.sql("select * from PR_TABLE6")
    assert(countdf1.count() == 1000)
    val excatJoinKeys = snc.sql(s"select P.OrderRef, P.description from " +
        s"PR_TABLE5 P JOIN PR_TABLE6 R ON P.OrderId = R.OrderId AND " +
        s"P.OrderRef = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, shuffleExpected = true)
  }

  test("Column PR table join with Row PR Table") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE7")

    snc.sql("CREATE TABLE PR_TABLE7(OrderId INT, description String, " +
        "OrderRef INT) USING row " +
        "options (" +
        "PARTITION_BY 'OrderId, OrderRef')")

    refDf.write.insertInto("PR_TABLE7")

    val countdf = snc.sql("select * from PR_TABLE7")
    assert(countdf.count() == 1000)

    snc.sql("DROP TABLE IF EXISTS PR_TABLE8")

    snc.sql("CREATE TABLE PR_TABLE8(OrderId INT ,description String, " +
        "OrderRef INT) USING column " +
        "options (" +
        "PARTITION_BY 'OrderId, OrderRef'," +
        "COLOCATE_WITH 'PR_TABLE7')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.insertInto("PR_TABLE8")
    val countdf1 = snc.sql("select * from PR_TABLE8")
    assert(countdf1.count() == 1000)
    val excatJoinKeys = snc.sql(s"select P.ORDERREF, P.DESCRIPTION from " +
        s"PR_TABLE7 P JOIN PR_TABLE8 R ON P.ORDERID = R.OrderId AND " +
        s"P.ORDERREF = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, shuffleExpected = false)
    assert(excatJoinKeys.count() === 500)

    snc.sql("DROP TABLE IF EXISTS PR_TABLE8")
  }

  test("Row PR table join with PR Table with unequal partitions") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    val snc = SnappyContext(sc)
    snc.conf.setConfString("spark.sql.autoBroadcastJoinThreshold", "-1")
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE9")

    snc.sql("CREATE TABLE PR_TABLE9(OrderId INT NOT NULL,description String, " +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId, OrderRef')")

    refDf.write.insertInto("PR_TABLE9")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE10")

    snc.sql("CREATE TABLE PR_TABLE10(OrderId INT NOT NULL,description String," +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId,OrderRef'," +
        "BUCKETS '213')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf = snc.createDataFrame(dimension2)
    dimensionDf.write.format("row").mode(SaveMode.Append).options(props)
        .saveAsTable("PR_TABLE10")
    val excatJoinKeys = snc.sql(s"select P.ORDERREF, P.DESCRIPTION from " +
        s"PR_TABLE9 P JOIN PR_TABLE10 R ON P.ORDERID = R.OrderId AND " +
        s"P.ORDERREF = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, shuffleExpected = true)
    assert(excatJoinKeys.count() === 500)
  }

  test("More than two table joins") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 10 + 1)))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE11")

    snc.sql("CREATE TABLE PR_TABLE11(OrderId INT NOT NULL,description String," +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId, OrderRef')")

    refDf.write.insertInto("PR_TABLE11")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE12")

    snc.sql("CREATE TABLE PR_TABLE12(OrderId INT NOT NULL,description String," +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId,OrderRef')")

    val dimension2 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf2 = snc.createDataFrame(dimension2)
    dimensionDf2.write.insertInto("PR_TABLE12")

    snc.sql("DROP TABLE IF EXISTS PR_TABLE13")

    snc.sql("CREATE TABLE PR_TABLE13(OrderId INT NOT NULL,description String," +
        "OrderRef INT) USING row options (" +
        "PARTITION_BY 'OrderId,OrderRef'," +
        "BUCKETS '213')")

    val dimension3 = sc.parallelize(
      (1 to 1000).map(i => TestData2(i, i.toString, i % 5 + 1)))

    val dimensionDf3 = snc.createDataFrame(dimension3)
    dimensionDf3.write.insertInto("PR_TABLE13")

    val excatJoinKeys = snc.sql(s"select P.ORDERREF, P.DESCRIPTION from " +
        s"PR_TABLE11 P ,PR_TABLE12 R, PR_TABLE13 Q where" +
        s" P.ORDERID = R.OrderId AND P.ORDERREF = R.OrderRef " +
        s"AND " +
        s"R.ORDERID = Q.OrderId AND R.ORDERREF = Q.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, shuffleExpected = true)
    assert(excatJoinKeys.count() === 500)
  }

  test("SnappyAggregation partitioning") {

    val dimension1 = sc.parallelize(
      (1 to 1000).map(i => TestDatak(i % 10, i.toString, i % 10)))
    val refDf = snc.createDataFrame(dimension1)
    snc.sql("DROP TABLE IF EXISTS PR_TABLE20")

    snc.sql("CREATE TABLE PR_TABLE20(OrderId INT, description String, " +
        "OrderRef INT) USING column options (" +
        "PARTITION_BY 'OrderId,OrderRef')")
    refDf.write.insertInto("PR_TABLE20")
    val groupBy1 = snc.sql(s"select  OrderRef, orderId from pr_table20 group by OrderRef, orderId")
    checkForShuffle(groupBy1.logicalPlan, snc, shuffleExpected = false)
    val groupBy2 = snc.sql(s"select  orderId, sum(orderRef) from pr_table20 group by orderId")
    checkForShuffle(groupBy2.logicalPlan, snc, shuffleExpected = true)
  }

  test("SNAP-2351") {

    snc.sql(s"create table trade.customers" +
        s" (cid int not null, cust_name varchar(100), " +
        s"since date, addr varchar(100), tid int, " +
        s"primary key (cid))  USING ROW OPTIONS (partition_by 'cid', buckets '8')")
    snc.sql(s"create table trade.networth (cid int not null, " +
        s"cash decimal (30, 20), securities decimal (30, 20), " +
        s"loanlimit int, availloan decimal (30, 20),  tid int, " +
        s"constraint netw_pk primary key (cid), " +
        s"constraint cust_newt_fk foreign key (cid) " +
        s"references trade.customers (cid) on delete restrict, " +
        s"constraint cash_ch check (cash>=0), " +
        s"constraint sec_ch check (securities >=0), " +
        s"constraint availloan_ck check (loanlimit>=availloan and availloan >=0)) " +
        s"USING ROW OPTIONS (partition_by 'cid', colocate_with 'trade.customers', buckets '8')")
    snc.sql(s"insert into trade.customers values(1,'abc','2012-01-14','abc-xyz',1)")
    snc.sql(s"insert into trade.customers values(2,'aaa','2012-01-14','aaa-xyz',1)")
    snc.sql(s"insert into trade.customers values(3,'bbb','2012-02-14','abb-xyz',1)")
    snc.sql(s"insert into trade.customers values(4,'ccc','2012-02-16','ccc-xyz',1)")
    snc.sql(s"insert into trade.customers values(5,'ddd','2012-01-16','ddd-xyz',1)")
    snc.sql(s"insert into trade.customers values(6,'eee','2012-03-17','eee-xyz',1)")
    snc.sql(s"insert into trade.networth values(1,10.2,11.2,10000,5000,1)")
    snc.sql(s"insert into trade.networth values(2,10.2,11.2,10000,5000,1)")
    snc.sql(s"insert into trade.networth values(3,13.2,11.2,15000,8000,1)")
    snc.sql(s"insert into trade.networth values(4,13.2,11.2,12000,3000,1)")
    snc.sql(s"insert into trade.networth values(5,13.2,14.2,20000,10000,1)")
    snc.sql(s"insert into trade.networth values(6,15.2,12.2,25000,15000,1)")
    snc.sql(s"set spark.sql.autoBroadcastJoinThreshold = -1")
    var df = snc.sql(s"select n.cid, cust_name, n.securities, n.cash, n.tid, " +
        s"c.cid from trade.customers c, trade.networth n where  n.cid = c.cid" +
        s" and n.tid = 1 and c.cid > 3")
    assert(df.collect().size === 3)
    df = snc.sql(s"select n.cid, cust_name, n.securities, n.cash, n.tid, c.cid" +
        s" from trade.customers c, trade.networth n where n.cid = c.cid" +
        s" and n.tid = 1 and c.cid > 5")
    assert(df.collect().size === 1)
  }

  test("SNAP-2443") {
    val testDF = snc.range(100000).selectExpr("id")
    var splits = testDF.randomSplit(Array(0.7, 0.3))
    var randomTraining = splits(0)
    var randomTesting = splits(1)
    randomTraining.createOrReplaceTempView("randomTraining")
    var summary = snc.sql("select sum(1) from randomTraining")
    val one = summary.collect()(0)(0).asInstanceOf[Long]
    splits = testDF.randomSplit(Array(0.5, 0.5))
    randomTraining = splits(0)
    randomTesting = splits(1)
    randomTraining.createOrReplaceTempView("randomTraining")
    summary = snc.sql("select sum(1) from randomTraining")
    val two = summary.collect()(0)(0).asInstanceOf[Long]
    assert(two < one)

  }


  def partitionToPartitionJoinAssertions(snc: SnappyContext,
      t1: String, t2: String): Unit = {

    val nullableEquality = snc.sql(s"select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderId = R.OrderId" +
        s" AND P.OrderRef <=> R.OrderRef")
    // TODO Why an exchange is needed for coalesce.
    checkForShuffle(nullableEquality.logicalPlan, snc, shuffleExpected = true)
    assert(nullableEquality.count() === 500)

    val withCoalesce = snc.sql(s"select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderId = R.OrderId" +
        s" AND coalesce(P.OrderRef,0) = coalesce(R.OrderRef,0)")
    // TODO Why an exchange is needed for coalesce.

    checkForShuffle(withCoalesce.logicalPlan, snc, shuffleExpected = true)
    assert(withCoalesce.count() === 500)

    val excatJoinKeys = snc.sql(s"select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderId = R.OrderId AND P.OrderRef = R.OrderRef")
    checkForShuffle(excatJoinKeys.logicalPlan, snc, shuffleExpected = false)
    // Make sure aggregation is working with non-shuffled joins
    assert(excatJoinKeys.count() === 500)

    // Reverse the join keys
    val reverseJoinSQL = if (isDifferentJoinOrderSupported) {
      "select P.OrderRef, P.description from " +
          s"$t1 P JOIN $t2 R ON P.OrderRef = R.OrderRef " +
          "AND P.OrderId = R.OrderId"
    } else {
      "select P.OrderRef, P.description from " +
          s"$t1 P JOIN $t2 R ON P.OrderId = R.OrderId " +
          "AND P.OrderRef = R.OrderRef"
    }
    val reverseJoinKeys = snc.sql(reverseJoinSQL)
    checkForShuffle(reverseJoinKeys.logicalPlan, snc, shuffleExpected = false)

    // Partial join keys
    val partialJoinKeys = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P JOIN $t2 R ON P.OrderRef = R.OrderRef")
    checkForShuffle(partialJoinKeys.logicalPlan, snc, shuffleExpected = true)

    // More join keys than partitioning keys
    val moreJoinSQL = if (isDifferentJoinOrderSupported) {
      "select P.OrderRef, P.description from " +
          s"$t1 P JOIN $t2 R ON P.OrderRef = R.OrderRef AND " +
          "P.OrderId = R.OrderId AND P.description = R.description"
    } else {
      "select P.OrderRef, P.description from " +
          s"$t1 P JOIN $t2 R ON P.OrderId = R.OrderId AND " +
          "P.OrderRef = R.OrderRef AND P.description = R.description"
    }
    val moreJoinKeys = snc.sql(moreJoinSQL)
    checkForShuffle(moreJoinKeys.logicalPlan, snc,
      shuffleExpected = !isDifferentJoinOrderSupported)


    val leftSemijoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P LEFT SEMI JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(leftSemijoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(leftSemijoinDF.count() === 500)

    val innerJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P INNER JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(innerJoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(innerJoinDF.count() === 500)

    val leftJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P LEFT JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(leftJoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(leftJoinDF.count() == 1000)

    val rightJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P RIGHT JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(rightJoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(rightJoinDF.count() == 1000)

    val leftOuterJoinDF = snc.sql("select P.OrderRef, P.description " +
        s"from $t1 P LEFT OUTER JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(leftOuterJoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(leftOuterJoinDF.count() == 1000)

    val rightOuterJoinDF = snc.sql("select P.OrderRef, P.description " +
        s"from $t1 P RIGHT OUTER JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(rightOuterJoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(rightOuterJoinDF.count() == 1000)

    val fullJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P FULL JOIN $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(fullJoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(fullJoinDF.count() == 1500)

    val fullOuterJoinDF = snc.sql("select P.OrderRef, P.description from " +
        s"$t1 P FULL OUTER JOIN   $t2 R ON P.OrderId = R.OrderId " +
        "AND P.OrderRef = R.OrderRef")
    // We don't expect a shuffle here
    checkForShuffle(fullOuterJoinDF.logicalPlan, snc, shuffleExpected = false)
    assert(fullOuterJoinDF.count() == 1500)
  }

  protected def isDifferentJoinOrderSupported: Boolean = true
}
