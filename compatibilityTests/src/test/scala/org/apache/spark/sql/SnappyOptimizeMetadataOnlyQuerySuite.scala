/* 
* Licensed to the Apache Software Foundation (ASF) under one or more 
* contributor license agreements.  See the NOTICE file distributed with 
* this work for additional information regarding copyright ownership. 
* The ASF licenses this file to You under the Apache License, Version 2.0 
* (the "License"); you may not use this file except in compliance with 
* the License.  You may obtain a copy of the License at 
* 
*    http://www.apache.org/licenses/LICENSE-2.0 
* 
* Unless required by applicable law or agreed to in writing, software 
* distributed under the License is distributed on an "AS IS" BASIS, 
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
* See the License for the specific language governing permissions and 
* limitations under the License. 
*/
package org.apache.spark.sql

import org.apache.spark.sql.execution.OptimizeMetadataOnlyQuerySuite
import org.apache.spark.sql.test.SharedSnappySessionContext

class SnappyOptimizeMetadataOnlyQuerySuite
    extends OptimizeMetadataOnlyQuerySuite with SharedSnappySessionContext{

  import testImplicits._

  override def beforeAll(): Unit = {
    val data = (1 to 10).map(i => (i, s"data-$i", i % 2, if ((i % 2) == 0) "even" else "odd"))
        .toDF("col1", "col2", "partcol1", "partcol2")
    data.write.partitionBy("partcol1", "partcol2").mode("append").saveAsTable("srcpart")
  }
}
