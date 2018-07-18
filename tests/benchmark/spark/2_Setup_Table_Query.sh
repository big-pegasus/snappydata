#!/usr/bin/env bash

source PerfRun.conf

# Create slaves configuration files
for element in "${slaves[@]}";
  do
	echo $element >> $sparkHome/conf/slaves
  done
echo "******************Created conf/slaves******************"

#Start master and slaves from master machines
ssh $master sh $sparkHome/sbin/start-all.sh

#Execute Spark App
sh $sparkHome/bin/spark-submit --master spark://$master:7077 $sparkProperties --class io.snappydata.benchmark.snappy.tpch.SparkApp $TPCHJar $dataDir $NumberOfLoadStages $Parquet $queries $sparkSqlProperties $IsDynamic $ResultCollection $WarmupRuns $AverageRuns 1 $rePartition $IsSupplierColumnTable $buckets_Supplier $buckets_Order_Lineitem $buckets_Cust_Part_PartSupp






