# Important Settings <a id="important-settings"></a>

Resource allocation is important for the execution of any job. If not configured correctly, the job can consume the entire clusters resources and cause execution failure because of memory and other related problems.

This section provides guidelines for configuring the following important settings:

* [Buckets](#buckets)

* [member-timeout](#member-timeout)

* [spark.local.dir](#spark-local-dir)

* [Operating System Settings](#os_setting)

* [Table Memory Requirements](#table-memory)

* [SnappyData Smart Connector mode and Local mode Settings](#smartconnector-local-settings)

<a id="buckets"></a>
## Buckets

A bucket is the smallest unit of in-memory storage for SnappyData tables. Data in a table is distributed evenly across all the buckets. When a new server joins or an existing server leaves the cluster, buckets are moved around in order to ensure that data is balanced across the nodes where the table is defined.

The default number of buckets in the SnappyData cluster mode is 128. In the local mode it is cores*2, subject to a maximum of 64 buckets and a minumum of 8 buckets.

The number of buckets has an impact on query performance, storage density, and ability to scale the system as data volumes grow.

If there are more buckets in a table than required, it means there is less data per bucket. For column tables, this may result in reduced compression that SnappyData achieves with various encodings. Similarly, if there are not enough buckets in a table, not enough partitions are created while running a query and hence cluster resources are not used efficiently. Also, if the cluster is scaled at a later point of time rebalancing may not be optimal.

For column tables, it is recommended to set a number of buckets such that each bucket has at least 100-150 MB of data. This attribute is set when [creating a table](../reference/sql_reference/create-table.md).

<a id="member-timeout"></a>
## member-timeout

The default [member-timeout](../configuring_cluster/property_description.md#member-timeout) in SnappyData cluster is 30 seconds. The default `spark.network.timeout` is 120 seconds and `spark.executor.heartbeatInterval` is 10 seconds as noted in the [Spark documents](https://spark.apache.org/docs/latest/configuration.html). </br> 
If applications require node failure detection to be faster, then these properties should be reduced accordingly (`spark.executor.heartbeatInterval` but must always be much lower than `spark.network.timeout` as specified in the Spark Documents). </br>
However, note that this can cause spurious node failures to be reported due to GC pauses. For example, the applications with reduced settings need to be resistant to job failures due to GC settings.

This attribute is set in the [configuration files](../configuring_cluster/configuring_cluster.md) **conf/locators**, **conf/servers** and **conf/leads** files. 

<a id="spark-local-dir"></a>
## spark.local.dir  

SnappyData writes table data on disk.  By default, the disk location that SnappyData uses is the directory specified using `-dir` option, while starting the member. 
SnappyData also uses temporary storage for storing intermediate data. The amount of intermediate data depends on the type of query and can be in the range of the actual data size. </br>
To achieve better performance, it is recommended to store temporary data on a different disk (preferably SSD) than the table data. This can be done by setting the `spark.local.dir` parameter.

This attribute is set in the [leads configuration files](../configuring_cluster/configuring_cluster.md#lead) **conf/leads**.

<a id="os_setting"></a>
##  Operating System Settings 

For best performance, the following operating system settings are recommended on the lead and server nodes.

**Ulimit** </br> 
Spark and SnappyData spawn a number of threads and sockets for concurrent/parallel processing so the server and lead node machines may need to be configured for higher limits of open files and threads/processes. </br>
</br>A minimum of 8192 is recommended for open file descriptors limit and nproc limit to be greater than 128K. 
</br>To change the limits of these settings for a user, the /etc/security/limits.conf file needs to be updated. A typical limits.conf used for SnappyData servers and leads looks like: 

```
ec2-user          hard    nofile      163840 
ec2-user          soft    nofile      16384
ec2-user          hard    nproc       unlimited
ec2-user          soft    nproc       524288
ec2-user          hard    sigpending  unlimited
ec2-user          soft    sigpending  524288
```
* `ec2-user` is the user running SnappyData.

**OS Cache Size**</br> 
When there is a lot of disk activity especially during table joins and during an eviction, the process may experience GC pauses. To avoid such situations, it is recommended to reduce the OS cache size by specifying a lower dirty ratio and less expiry time of the dirty pages.</br> 
The following are the typical configuration to be done on the machines that are running SnappyData processes. 

```
sudo sysctl -w vm.dirty_background_ratio=2
sudo sysctl -w vm.dirty_ratio=4
sudo sysctl -w vm.dirty_expire_centisecs=2000
sudo sysctl -w vm.dirty_writeback_centisecs=300
```

**Swap File** </br> 
Since modern operating systems perform lazy allocation, it has been observed that despite setting `-Xmx` and `-Xms` settings, at runtime, the operating system may fail to allocate new pages to the JVM. This can result in the process going down.</br>
It is recommended to set swap space on your system using the following commands.

```
# sets a swap space of 32 GB
sudo dd if=/dev/zero of=/var/swapfile.1 bs=1M count=32768
sudo chmod 600 /var/swapfile.1
sudo mkswap /var/swapfile.1
sudo swapon /var/swapfile.1
```

<a id="smartconnector-local-settings"></a>
## SnappyData Smart Connector mode and Local mode Settings

### Managing Executor Memory
For efficient loading of data from a Smart Connector application or a Local Mode application, all the partitions of the input data are processed in parallel by making use of all the available cores. Further, to have better ingestion speed, small internal columnar storage structures are created in the Spark application's cluster itself, which is then directly inserted into the required buckets of the column table in the SnappyData cluster.
These internal structures are in encoded form and for efficient encoding, some memory space is acquired upfront, which is independent of the amount of data to be loaded into the tables. </br>
For example, if there are 32 cores for the Smart Connector application and the number of buckets of the column table is equal or more than that, then, each of the 32 executor threads can take around 32MB of memory. This indicates that 32MB * 32MB (1 GB) of memory is required. Thus, the default of 1GB for executor memory is not sufficient and therefore a default of at least 2 GB is recommended in this case.

You can modify this setting in the `spark.executor.memory` property. For more information, refer to the [Spark documentation](https://spark.apache.org/docs/latest/configuration.html#available-properties).

### JVM settings for optimal performance
The JVM setting is set by default and the following is recommended only for local mode:

```-XX:-DontCompileHugeMethods -XX:+UnlockDiagnosticVMOptions -XX:ParGCCardsPerStrideChunk=4k```

Set in the **conf/locators**, **conf/leads**, and **conf/servers** file.