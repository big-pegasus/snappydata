# MEMORYANALYTICS

A SnappyData virtual table that provides information about the overhead and memory usage of user tables and indexes.

You can query the SYS.MEMORYANALYTICS table to obtain details about individual tables and indexes

|Column Name|Type|Length|Nullable|Contents|
------------ | ------------- | ------------|------------- |------------- |
|TABLE_NAME|VARCHAR|64|No|The full name of the table using the format <em>schema_name</em>.<em>table_name</em>.|
|INDEX_NAME|VARCHAR|64|Yes|Name of the index associated with the table.|
|INDEX_TYPE|VARCHAR|32|Yes|Description of the type of index associated with the table-- local or a global hash index, and whether the index is sorted.|
|ID|VARCHAR|128|No|Member ID of the member hosting the table.|
|HOST|VARCHAR|128|No|The SnappyData member to which the memory values apply.|
|CONSTANT_OVERHEAD|REAL|0|No|One-time memory overhead cost due to artifacts produced when a blank table is created.|
|ENTRY_SIZE|REAL|0|No|Entry overhead, in kilobytes. Only reflects the amount of memory required to hold the table row in memory but not including the memory to hold its key and value. (Excludes KEY_SIZE, CONSTANT_OVERHEAD, VALUE_SIZE and VALUE_SIZE_OFFHEAP below.)|
|KEY_SIZE|REAL|0|No|Key overhead, in kilobytes. Note that this column will only display a non-zero value when the table is set to overflow to disk and the complete row (in other words, the row value) is no longer held in memory.|
|VALUE_SIZE|REAL|0|No|The size, in kilobytes, of the table row data stored in the JVM heap. (This includes the Entry Size overhead.)|
|VALUE_SIZE_OFFHEAP|REAL|0|No|The size, in kilobytes, of the table row data stored in off-heap memory.|
|TOTAL_SIZE|REAL|0|No|Total size is the sum, in kilobytes, of the following columns:<br> * CONSTANT_OVERHEAD<br> * ENTRY_SIZE<br> * KEY_SIZE<br> * VALUE_SIZE<br> * VALUE_SIZE_OFFHEAP|
|NUM_ROWS|BIGINT|0|No|The total number of rows stored on the local SnappyData member. For a partitioned table, this includes all buckets for the table, as well as primary and secondary replicas.|
|NUM_KEYS_IN_MEMORY|BIGINT|0|No|The total number of keys stored in the heap for the table. Note that this column will only display a non-zero value when the table is set to overflow to disk and the complete row (in other words, the row's value) is no longer held in memory.|
|NUM_VALUES_IN_MEMORY|BIGINT|0|No|The total number of row values stored in the heap for the table.|
|NUM_VALUES_IN_OFFHEAP|BIGINT|0|No|The total number of row values stored in off-heap memory.|
|MEMORY|LONG VARCHAR|2147483647|No|Placeholder for future use.|


