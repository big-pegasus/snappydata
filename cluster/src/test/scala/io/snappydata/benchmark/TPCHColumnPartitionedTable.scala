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
package io.snappydata.benchmark

import java.io.{File, PrintStream}
import java.sql.Statement

import org.apache.spark.SparkContext
import org.apache.spark.sql.execution.benchmark.ColumnCacheBenchmark
import org.apache.spark.sql.snappy._
import org.apache.spark.sql.{DataFrame, SQLContext, SnappyContext}


// scalastyle:off println
object TPCHColumnPartitionedTable {

  def createPartTable_Memsql(stmt: Statement): Unit = {
    stmt.execute("CREATE TABLE PART  ( " +
        "P_PARTKEY     INTEGER NOT NULL," +
        "P_NAME        VARCHAR(55) NOT NULL," +
        "P_MFGR        VARCHAR(25) NOT NULL," +
        "P_BRAND       VARCHAR(10) NOT NULL," +
        "P_TYPE        VARCHAR(25) NOT NULL," +
        "P_SIZE        INTEGER NOT NULL," +
        "P_CONTAINER   VARCHAR(10) NOT NULL," +
        "P_RETAILPRICE DECIMAL(15,2) NOT NULL," +
        "P_COMMENT     VARCHAR(23) NOT NULL," +
        "KEY (P_PARTKEY) USING CLUSTERED COLUMNSTORE," +
        "SHARD KEY (P_PARTKEY))"
    )
    println("Created Table PART")
  }


  def createPartSuppTable_Memsql(stmt: Statement): Unit = {
    stmt.execute("CREATE TABLE PARTSUPP ( " +
        "PS_PARTKEY     INTEGER NOT NULL," +
        "PS_SUPPKEY     INTEGER NOT NULL," +
        "PS_AVAILQTY    INTEGER NOT NULL," +
        "PS_SUPPLYCOST  DECIMAL(15,2)  NOT NULL," +
        "PS_COMMENT     VARCHAR(199) NOT NULL," +
        "KEY (PS_PARTKEY) USING CLUSTERED COLUMNSTORE," +
        "SHARD KEY (PS_PARTKEY))"
    )
    println("Created Table PARTSUPP")
  }

  def createCustomerTable_Memsql(stmt: Statement): Unit = {
    stmt.execute("CREATE TABLE CUSTOMER ( " +
        "C_CUSTKEY     INTEGER NOT NULL," +
        "C_NAME        VARCHAR(25) NOT NULL," +
        "C_ADDRESS     VARCHAR(40) NOT NULL," +
        "C_NATIONKEY   INTEGER NOT NULL," +
        "C_PHONE       VARCHAR(15) NOT NULL," +
        "C_ACCTBAL     DECIMAL(15,2)   NOT NULL," +
        "C_MKTSEGMENT  VARCHAR(10) NOT NULL," +
        "C_COMMENT     VARCHAR(117) NOT NULL," +
        "KEY (C_CUSTKEY) USING CLUSTERED COLUMNSTORE," +
        "SHARD KEY (C_CUSTKEY))"
    )
    println("Created Table CUSTOMER")
  }

  def createOrderTable_Memsql(stmt: Statement): Unit = {

    stmt.execute("CREATE TABLE ORDERS  ( " +
        "O_ORDERKEY       INTEGER NOT NULL," +
        "O_CUSTKEY        INTEGER NOT NULL," +
        "O_ORDERSTATUS    CHAR(1) NOT NULL," +
        "O_TOTALPRICE     DECIMAL(15,2) NOT NULL," +
        "O_ORDERDATE      DATE NOT NULL," +
        "O_ORDERPRIORITY  CHAR(15) NOT NULL," +
        "O_CLERK          CHAR(15) NOT NULL," +
        "O_SHIPPRIORITY   INTEGER NOT NULL," +
        "O_COMMENT        VARCHAR(79) NOT NULL," +
        "KEY (O_ORDERKEY) USING CLUSTERED COLUMNSTORE," +
        "SHARD KEY(O_ORDERKEY))"
    )
    println("Created Table ORDERS")
  }

  def createLineItemTable_Memsql(stmt: Statement): Unit = {
    stmt.execute("CREATE TABLE LINEITEM ( L_ORDERKEY    INTEGER NOT NULL," +
        "L_PARTKEY     INTEGER NOT NULL," +
        "L_SUPPKEY     INTEGER NOT NULL," +
        "L_LINENUMBER  INTEGER NOT NULL," +
        "L_QUANTITY    DECIMAL(15,2) NOT NULL," +
        "L_EXTENDEDPRICE  DECIMAL(15,2) NOT NULL," +
        "L_DISCOUNT    DECIMAL(15,2) NOT NULL," +
        "L_TAX         DECIMAL(15,2) NOT NULL," +
        "L_RETURNFLAG  CHAR(1) NOT NULL," +
        "L_LINESTATUS  CHAR(1) NOT NULL," +
        "L_SHIPDATE    DATE NOT NULL," +
        "L_COMMITDATE  DATE NOT NULL," +
        "L_RECEIPTDATE DATE NOT NULL," +
        "L_SHIPINSTRUCT CHAR(25) NOT NULL," +
        "L_SHIPMODE     CHAR(10) NOT NULL," +
        "L_COMMENT      VARCHAR(44) NOT NULL," +
        "KEY (L_ORDERKEY) USING CLUSTERED COLUMNSTORE," +
        "SHARD KEY (L_ORDERKEY)) "
    )

    println("Created Table LINEITEM")
  }

  var CREATE_PARQUET: Boolean = java.lang.Boolean.getBoolean("snappydata.test.create_parquet")

  def createPopulateOrderTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String = "128", loadPerfPrintStream: PrintStream = null, redundancy : String = "0",
      persistence: Boolean = false, persistence_type: String = "", numberOfLoadingStage : Int = 1,
      isParquet : Boolean = false) : Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    var orderDF: DataFrame = null
    var unionOrderDF: DataFrame = null
    // use parquet data if available
    for (i <- 1 to numberOfLoadingStage) {
      if (isParquet) {
        orderDF = sqlContext.read.format("parquet").load(s"$path/parquet_orders_$i")
      } else {
        val orderData = sc.textFile(s"$path/orders.tbl.$i")
        val orderReadings = orderData.map(s => s.split('|')).map(
          s => TPCHTableSchema.parseOrderRow(s))
        val orderDF1 = sqlContext.createDataFrame(orderReadings)
        val newSchema = TPCHTableSchema.newOrderSchema(orderDF1.schema)

        orderDF = ColumnCacheBenchmark.applySchema(orderDF1, newSchema)
        if (CREATE_PARQUET) {
          orderDF.write.format("parquet").save(s"$path/parquet_orders_$i")
        }
      }
      val newSchema = TPCHTableSchema.newOrderSchema(orderDF.schema)
      if (isSnappy) {
        if (i == 1) {
          var p1 = Map(("PARTITION_BY" -> "o_orderkey"), ("BUCKETS" -> buckets),
            ("REDUNDANCY" -> redundancy))
          if (persistence) {
            p1 += "PERSISTENT" -> s"$persistence_type"
          }
          val snappyContext = sqlContext.asInstanceOf[SnappyContext]
          snappyContext.createTable("ORDERS", "column", newSchema, p1)
        }
        orderDF.write.insertInto("ORDERS")
      } else {
        if (i == 1) {
          unionOrderDF = orderDF
        } else {
          unionOrderDF = unionOrderDF.union(orderDF)
        }
      }
    }
    if (!isSnappy) {
      if (!buckets.equals("0")) {
        val rePartitionedDF = unionOrderDF.repartition(buckets.toInt,
          unionOrderDF("o_orderkey"))
        rePartitionedDF.createOrReplaceTempView("ORDERS")
      } else {
        unionOrderDF.createOrReplaceTempView("ORDERS")
      }
      sqlContext.cacheTable("ORDERS")
      sqlContext.table("ORDERS").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create ORDERS Table : ${endTime - startTime}")
    }
  }

  def createAndPopulateOrder_CustTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String, loadPerfPrintStream: PrintStream = null): Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    val orderData = sc.textFile(s"$path/orders.tbl")
    val orderReadings = orderData.map(s => s.split('|')).map(s => TPCHTableSchema.parseOrderRow(s))
    val orderDF = sqlContext.createDataFrame(orderReadings)
    val newSchema = TPCHTableSchema.newOrderSchema(orderDF.schema)
    if (isSnappy) {
      val p1 = Map(("PARTITION_BY" -> "o_custkey"), ("BUCKETS" -> buckets), ("COLOCATE_WITH" ->
          "CUSTOMER"))
      val snappyContext = sqlContext.asInstanceOf[SnappyContext]
      snappyContext.dropTable("ORDERS_CUST", ifExists = true)
      snappyContext.createTable("ORDERS_CUST", "column", newSchema, p1)
      orderDF.write.insertInto("ORDERS_CUST")
      val endTime = System.currentTimeMillis()
      if (loadPerfPrintStream != null) {
        loadPerfPrintStream.println("Time taken to create ORDERS_CUST Table : " +
            (endTime - startTime))
      }
    }
  }


  def createPopulateLineItemTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String = "128", loadPerfPrintStream: PrintStream = null, redundancy : String = "0",
      persistence: Boolean = false, persistence_type: String = "", numberOfLoadingStage : Int = 1,
      isParquet : Boolean = false) : Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    var lineItemDF: DataFrame = null
    var unionLineItemDF: DataFrame = null
    // use parquet data if available
    for (i <- 1 to numberOfLoadingStage) {
      if (isParquet) {
        lineItemDF = sqlContext.read.format("parquet").load(s"$path/parquet_lineitem_$i")
      } else {
        val lineItemData = sc.textFile(s"$path/lineitem.tbl.$i")
        val lineItemReadings = lineItemData.map(s => s.split('|')).map(s => TPCHTableSchema
            .parseLineItemRow(s))
        val lineItemDF1 = sqlContext.createDataFrame(lineItemReadings)
        val newSchema = TPCHTableSchema.newLineItemSchema(lineItemDF1.schema)

        lineItemDF = ColumnCacheBenchmark.applySchema(lineItemDF1, newSchema)
        if (CREATE_PARQUET) {
          lineItemDF.write.format("parquet").save(s"$path/parquet_lineitem_$i")
        }
      }
      val newSchema = TPCHTableSchema.newLineItemSchema(lineItemDF.schema)
      if (isSnappy) {
        if (i == 1) {
          var p1 = Map(("PARTITION_BY" -> "l_orderkey"), ("COLOCATE_WITH" -> "ORDERS"),
            ("BUCKETS" -> buckets), ("REDUNDANCY" -> redundancy))
          if (persistence) {
            p1 += "PERSISTENT" -> s"$persistence_type"
          }
          val snappyContext = sqlContext.asInstanceOf[SnappyContext]
          snappyContext.createTable("LINEITEM", "column", newSchema, p1)
        }
        lineItemDF.write.insertInto("LINEITEM")
      } else {
        if (i == 1) {
          unionLineItemDF = lineItemDF
        } else {
          unionLineItemDF = unionLineItemDF.union(lineItemDF)
        }
      }
    }
    if(!isSnappy){
      if (!buckets.equals("0")) {
        val rePartitionedDF = unionLineItemDF.repartition(buckets.toInt,
          unionLineItemDF("l_orderkey"))
        rePartitionedDF.createOrReplaceTempView("LINEITEM")
        } else {
          unionLineItemDF.createOrReplaceTempView("LINEITEM")
        }
      sqlContext.cacheTable("LINEITEM")
      sqlContext.table("LINEITEM").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create LINEITEM Table : ${endTime - startTime}")
    }
  }

  def createAndPopulateLineItem_partTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String, loadPerfPrintStream: PrintStream = null): Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    val lineItemData = sc.textFile(s"$path/lineitem.tbl")
    val lineItemReadings = lineItemData.map(s => s.split('|')).map(s => TPCHTableSchema
        .parseLineItemRow(s))
    val lineItemPartDF = sqlContext.createDataFrame(lineItemReadings)
    val newSchema = TPCHTableSchema.newLineItemSchema(lineItemPartDF.schema)
    if (isSnappy) {
      val p1 = Map(("PARTITION_BY" -> "l_partkey"), ("COLOCATE_WITH" -> "PART"), ("BUCKETS" ->
          buckets))

      val snappyContext = sqlContext.asInstanceOf[SnappyContext]
      snappyContext.dropTable("LINEITEM_PART", ifExists = true)
      snappyContext.createTable("LINEITEM_PART", "column", newSchema, p1)
      lineItemPartDF.write.insertInto("LINEITEM_PART")
      val endTime = System.currentTimeMillis()
      if (loadPerfPrintStream != null) {
        loadPerfPrintStream.println("Time taken to create LINEITEM_PART Table : " +
            (endTime - startTime))
      }
    }
  }

  def createPopulateCustomerTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String = "128", loadPerfPrintStream: PrintStream = null, redundancy : String = "0",
      persistence: Boolean = false, persistence_type: String = "", numberOfLoadingStage : Int = 1,
      isParquet : Boolean = false) : Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    var customerDF: DataFrame = null
    var unionCustomerDF: DataFrame = null
    for (i <- 1 to numberOfLoadingStage) {
      // use parquet data if available
      if (isParquet) {
        customerDF = sqlContext.read.format("parquet").load(s"$path/parquet_customer_$i")
      } else {
        val customerData = sc.textFile(s"$path/customer.tbl.$i")
        val customerReadings = customerData.map(s => s.split('|')).map(s => TPCHTableSchema
            .parseCustomerRow(s))
        val customerDF1 = sqlContext.createDataFrame(customerReadings)
        val newSchema = TPCHTableSchema.newCustomerSchema(customerDF1.schema)

        customerDF = ColumnCacheBenchmark.applySchema(customerDF1, newSchema)
        if (CREATE_PARQUET) {
          customerDF.write.format("parquet").save(s"$path/parquet_customer_$i")
        }
      }
      val newSchema = TPCHTableSchema.newCustomerSchema(customerDF.schema)
      if (isSnappy) {
        if (i == 1) {
          var p1 = Map(("PARTITION_BY" -> "c_custkey"), ("BUCKETS" -> buckets),
            ("REDUNDANCY" -> redundancy))
          if (persistence) {
            p1 += "PERSISTENT" -> s"$persistence_type"
          }

          val snappyContext = sqlContext.asInstanceOf[SnappyContext]
          snappyContext.createTable("CUSTOMER", "column", newSchema, p1)
        }
        customerDF.write.insertInto("CUSTOMER")
      } else {
        if (i == 1) {
          unionCustomerDF = customerDF
        } else {
          unionCustomerDF = unionCustomerDF.union(customerDF)
        }
      }
    }
    if(!isSnappy){
      if (!buckets.equals("0")) {
        val rePartitionedDF = unionCustomerDF.repartition(buckets.toInt,
          unionCustomerDF("c_custkey"))
        rePartitionedDF.createOrReplaceTempView("CUSTOMER")
      } else {
        unionCustomerDF.createOrReplaceTempView("CUSTOMER")
      }
      sqlContext.cacheTable("CUSTOMER")
      sqlContext.table("CUSTOMER").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create CUSTOMER Table : ${endTime - startTime}")
    }
  }


  def createPopulatePartTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String = "128", loadPerfPrintStream: PrintStream = null, redundancy : String = "0",
      persistence: Boolean = false, persistence_type: String = "", numberOfLoadingStage : Int = 1,
      isParquet : Boolean = false) : Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    var partDF: DataFrame = null
    var unionPartDF: DataFrame = null
    for(i <- 1 to numberOfLoadingStage) {
      // use parquet data if available
      if (isParquet) {
        partDF = sqlContext.read.format("parquet").load(s"$path/parquet_part_$i")
      } else {
        val partData = sc.textFile(s"$path/part.tbl.$i")
        val partReadings = partData.map(s => s.split('|')).map(s => TPCHTableSchema.parsePartRow(s))
        val partDF1 = sqlContext.createDataFrame(partReadings)
        val newSchema = TPCHTableSchema.newPartSchema(partDF1.schema)

        partDF = ColumnCacheBenchmark.applySchema(partDF1, newSchema)
        if (CREATE_PARQUET) {
          partDF.write.format("parquet").save(s"$path/parquet_part_$i")
        }
      }
      val newSchema = TPCHTableSchema.newPartSchema(partDF.schema)
      if (isSnappy) {
        if (i == 1) {
          var p1 = Map(("PARTITION_BY" -> "p_partkey"), ("BUCKETS" -> buckets),
            ("REDUNDANCY" -> redundancy))
          if (persistence) {
            p1 += "PERSISTENT" -> s"$persistence_type"
          }
          val snappyContext = sqlContext.asInstanceOf[SnappyContext]
          snappyContext.createTable("PART", "column", newSchema, p1)
        }
        partDF.write.insertInto("PART")
      } else {
        if (i == 1) {
          unionPartDF = partDF
        } else {
          unionPartDF = unionPartDF.union(partDF)
        }
      }
    }
    if(!isSnappy){
      if (!buckets.equals("0")) {
        val rePartitionedDF = unionPartDF.repartition(buckets.toInt,
          unionPartDF("p_partkey"))
        rePartitionedDF.createOrReplaceTempView("PART")
      } else {
        unionPartDF.createOrReplaceTempView("PART")
      }
      sqlContext.cacheTable("PART")
      sqlContext.table("PART").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create PART Table : ${endTime - startTime}")
    }
  }

  def createPopulatePartSuppTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String = "128", loadPerfPrintStream: PrintStream = null, redundancy : String = "0",
      persistence: Boolean = false, persistence_type: String = "", numberOfLoadingStage : Int = 1,
      isParquet : Boolean = false) : Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    var partSuppDF: DataFrame = null
    var unionPartSuppDF: DataFrame = null
    for (i <- 1 to numberOfLoadingStage) {
      // use parquet data if available
      if (isParquet) {
        partSuppDF = sqlContext.read.format("parquet").load(s"$path/parquet_partsupp_$i")
      } else {
        val partSuppData = sc.textFile(s"$path/partsupp.tbl.$i")
        val partSuppReadings = partSuppData.map(s => s.split('|')).map(s => TPCHTableSchema
            .parsePartSuppRow(s))
        val partSuppDF1 = sqlContext.createDataFrame(partSuppReadings)
        val newSchema = TPCHTableSchema.newPartSuppSchema(partSuppDF1.schema)

        partSuppDF = ColumnCacheBenchmark.applySchema(partSuppDF1, newSchema)
        if (CREATE_PARQUET) {
          partSuppDF.write.format("parquet").save(s"$path/parquet_partsupp_$i")
        }
      }
      val newSchema = TPCHTableSchema.newPartSuppSchema(partSuppDF.schema)
      if (isSnappy) {
        if (i == 1) {
          var p1 = Map(("PARTITION_BY" -> "ps_partkey"), ("BUCKETS" -> buckets),
            ("COLOCATE_WITH" -> "PART"), ("REDUNDANCY" -> redundancy))
          if (persistence) {
            p1 += "PERSISTENT" -> s"$persistence_type"
          }
          val snappyContext = sqlContext.asInstanceOf[SnappyContext]
          snappyContext.createTable("PARTSUPP", "column", newSchema, p1)
        }
        partSuppDF.write.insertInto("PARTSUPP")
      } else {
        if (i == 1) {
          unionPartSuppDF = partSuppDF
        } else {
          unionPartSuppDF = unionPartSuppDF.union(partSuppDF)
        }
      }
    }
    if (!isSnappy) {
      if (!buckets.equals("0")) {
        val rePartitionedDF = unionPartSuppDF.repartition(buckets.toInt,
          unionPartSuppDF("ps_partkey"))
        rePartitionedDF.createOrReplaceTempView("PARTSUPP")
      } else {
        unionPartSuppDF.createOrReplaceTempView("PARTSUPP")
      }
      sqlContext.cacheTable("PARTSUPP")
      sqlContext.table("PARTSUPP").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create PARTSUPP Table : ${endTime - startTime}")
    }
  }


  def createAndPopulateOrderSampledTable(sc: SparkContext, path: String): Unit = {
    val snappyContext = SnappyContext(sc)
    val orderDF = snappyContext.table("ORDERS")
    val orderSampled = orderDF.stratifiedSample(Map(
      "qcs" -> "O_ORDERDATE", // O_SHIPPRIORITY
      "fraction" -> 0.03,
      "strataReservoirSize" -> 50))
    orderSampled.registerTempTable("ORDERS_SAMPLED")
    snappyContext.cacheTable("orders_sampled")
    println("Created Sampled Table ORDERS_SAMPLED " + snappyContext.sql(
      "select count(*) as sample_count from orders_sampled").collectAsList())
  }

  def createAndPopulateLineItemSampledTable(sc: SparkContext, path: String): Unit = {
    val snappyContext = SnappyContext(sc)
    val lineOrderDF = snappyContext.table("LINEITEM")
    val lineOrderSampled = lineOrderDF.stratifiedSample(Map(
      "qcs" -> "L_SHIPDATE", // L_RETURNFLAG
      "fraction" -> 0.03,
      "strataReservoirSize" -> 50))
    println(" Logic relation while creation " + lineOrderSampled.logicalPlan.output)
    lineOrderSampled.registerTempTable("LINEITEM_SAMPLED")
    snappyContext.cacheTable("lineitem_sampled")
    println("Created Sampled Table LINEITEM_SAMPLED " + snappyContext.sql(
      "select count(*) as sample_count from lineitem_sampled").collectAsList())
  }

  def createAndPopulateNationTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String, loadPerfPrintStream: PrintStream = null): Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    val nationData = sc.textFile(s"$path/nation.tbl")
    val nationreadings = nationData.map(s => s.split('|')).map(s => TPCHTableSchema
        .parseNationRow(s))
    val nationdf = sqlContext.createDataFrame(nationreadings)
    val newSchema = TPCHTableSchema.newNationSchema(nationdf.schema)
    if (isSnappy) {
      val p1 = Map(("PARTITION_BY" -> "N_NATIONKEY"), ("BUCKETS" -> buckets))
      val snappyContext = sqlContext.asInstanceOf[SnappyContext]
      snappyContext.createTable("NATION", "column", newSchema, p1)
      nationdf.write.insertInto("NATION")
    } else {
      nationdf.createOrReplaceTempView("NATION")
      sqlContext.cacheTable("NATION")
      sqlContext.table("NATION").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create NATION Table : ${endTime - startTime}")
    }
  }

  def createAndPopulateRegionTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String, loadPerfPrintStream: PrintStream = null): Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    val regionData = sc.textFile(s"$path/region.tbl")
    val regionreadings = regionData.map(s => s.split('|')).map(s => TPCHTableSchema
        .parseRegionRow(s))
    val regiondf = sqlContext.createDataFrame(regionreadings)
    val newSchema = TPCHTableSchema.newRegionSchema(regiondf.schema)
    if (isSnappy) {
      val p1 = Map(("PARTITION_BY" -> "R_REGIONKEY"), ("BUCKETS" -> buckets))
      val snappyContext = sqlContext.asInstanceOf[SnappyContext]
      snappyContext.createTable("REGION", "column", newSchema, p1)
      regiondf.write.insertInto("REGION")
    } else {
      regiondf.createOrReplaceTempView("REGION")
      sqlContext.cacheTable("REGION")
      sqlContext.table("REGION").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create REGION Table : ${endTime - startTime}")
    }
  }

  def createAndPopulateSupplierTable(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String = "128", loadPerfPrintStream: PrintStream = null, redundancy : String = "0",
      persistence: Boolean = false, persistence_type: String = "", numberOfLoadingStage : Int = 1,
      isParquet : Boolean = false): Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    var suppDF: DataFrame = null
    var unionSuppDF: DataFrame = null

    for (i <- 1 to numberOfLoadingStage) {
      // use parquet data if available
      val parquetDir = s"$path/parquet_supplier_$i"
      if (isParquet && new File(parquetDir).exists()) {
        suppDF = sqlContext.read.format("parquet").load(parquetDir)
      } else {
        val suppData = sc.textFile(s"$path/supplier.tbl.$i")
        val suppReadings = suppData.map(s => s.split('|')).map(s => TPCHTableSchema
            .parseSupplierRow(s))
        val suppDF1 = sqlContext.createDataFrame(suppReadings)
        val newSchema = TPCHTableSchema.newSupplierSchema(suppDF1.schema)

        suppDF = ColumnCacheBenchmark.applySchema(suppDF1, newSchema)
        if (CREATE_PARQUET) {
          suppDF.write.format("parquet").save(s"$path/parquet_supplier_$i")
        }
      }
      val newSchema = TPCHTableSchema.newSupplierSchema(suppDF.schema)
      if (isSnappy) {
        if (i == 1) {
          var p1 = Map(("PARTITION_BY" -> "S_SUPPKEY"), ("BUCKETS" -> buckets),
            ("REDUNDANCY" -> redundancy))
          if (persistence) {
            p1 += "PERSISTENT" -> s"$persistence_type"
          }
          val snappyContext = sqlContext.asInstanceOf[SnappyContext]
          snappyContext.createTable("SUPPLIER", "column", newSchema, p1)
        }
        suppDF.write.insertInto("SUPPLIER")
      } else {
        if (i == 1) {
          unionSuppDF = suppDF
        } else {
          unionSuppDF = unionSuppDF.union(suppDF)
        }
      }
    }
    if (!isSnappy) {
      if (!buckets.equals("0")) {
        val rePartitionedDF = unionSuppDF.repartition(buckets.toInt,
          unionSuppDF("S_SUPPKEY"))
        rePartitionedDF.createOrReplaceTempView("SUPPLIER")
      } else {
        unionSuppDF.createOrReplaceTempView("SUPPLIER")
      }
      sqlContext.cacheTable("SUPPLIER")
      sqlContext.table("SUPPLIER").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create SUPPLIER Table : ${endTime - startTime}")
    }
  }

  def testLoadOrderTablePerformance(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String, loadPerfPrintStream: PrintStream = null): Unit = {

    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    val orderData = sc.textFile(s"$path/orders.tbl")
    val orderReadings = orderData.map(s => s.split('|')).map(s => TPCHTableSchema.parseOrderRow(s))
    val orderDF = sqlContext.createDataFrame(orderReadings)
    val newSchema = TPCHTableSchema.newOrderSchema(orderDF.schema)
    if (isSnappy) {
      val p1 = Map(("PARTITION_BY" -> "o_orderkey"), ("BUCKETS" -> buckets))
      val snappyContext = sqlContext.asInstanceOf[SnappyContext]
      snappyContext.createTable("ORDERS", "column", newSchema, p1)
      orderDF.write.insertInto("ORDERS")
    } else {
      var newOrderDF: DataFrame = null
      val numPartitions = buckets.toInt
      if (numPartitions > 0) {
        newOrderDF = orderDF.repartition(buckets.toInt, orderDF.col("o_orderkey"))
      } else {
        newOrderDF = orderDF.repartition(orderDF.col("o_orderkey"))
      }
      newOrderDF.createOrReplaceTempView("ORDERS")
      sqlContext.cacheTable("ORDERS")
      sqlContext.table("ORDERS").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create ORDERS Table : ${endTime - startTime}")
    }
  }

  def testLoadLineItemTablePerformance(sqlContext: SQLContext, path: String, isSnappy: Boolean,
      buckets: String, loadPerfPrintStream: PrintStream = null): Unit = {
    val sc = sqlContext.sparkContext
    val startTime = System.currentTimeMillis()
    val lineItemData = sc.textFile(s"$path/lineitem.tbl")
    val lineItemReadings = lineItemData.map(s => s.split('|')).map(s => TPCHTableSchema
        .parseLineItemRow(s))
    val lineItemDF = sqlContext.createDataFrame(lineItemReadings)
    val newSchema = TPCHTableSchema.newLineItemSchema(lineItemDF.schema)
    if (isSnappy) {
      val p1 = Map(("PARTITION_BY" -> "l_orderkey"), ("COLOCATE_WITH" -> "ORDERS"), ("BUCKETS" ->
          buckets))

      val snappyContext = sqlContext.asInstanceOf[SnappyContext]
      snappyContext.dropTable("LINEITEM", ifExists = true)
      snappyContext.createTable("LINEITEM", "column", newSchema, p1)
      lineItemDF.write.insertInto("LINEITEM")
    } else {
      var newLineItemDF: DataFrame = null
      val numPartitions = buckets.toInt
      if (numPartitions > 0) {
        newLineItemDF = lineItemDF.repartition(buckets.toInt, lineItemDF.col("l_orderkey"))
      } else {
        newLineItemDF = lineItemDF.repartition(lineItemDF.col("l_orderkey"))
      }
      newLineItemDF.createOrReplaceTempView("LINEITEM")
      sqlContext.cacheTable("LINEITEM")
      sqlContext.table("LINEITEM").count()
    }
    val endTime = System.currentTimeMillis()
    if (loadPerfPrintStream != null) {
      loadPerfPrintStream.println(s"Time taken to create LINEITEM Table : ${endTime - startTime}")
    }
  }
}
