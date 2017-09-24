#!/usr/bin/env bash

#
# Copyright (c) 2017 SnappyData, Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you
# may not use this file except in compliance with the License. You
# may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied. See the License for the specific language governing
# permissions and limitations under the License. See accompanying
# LICENSE file.
#

# Starts a lead instance on each machine specified in the conf/leads file.

function absPath() {
  perl -MCwd -le 'print Cwd::abs_path(shift)' "$1"
}
sbin="$(dirname "$(absPath "$0")")"

. "$sbin/snappy-config.sh"
. "$sbin/spark-config.sh"

. "$SNAPPY_HOME/bin/load-spark-env.sh"
. "$SNAPPY_HOME/bin/load-snappy-env.sh"


# Launch the slaves
if echo $@ | grep -qw start; then
  "$sbin/snappy-nodes.sh" lead cd "$SNAPPY_HOME" \; "$sbin/snappy-lead.sh" $@ $LEAD_STARTUP_OPTIONS
else
  "$sbin/snappy-nodes.sh" lead cd "$SNAPPY_HOME" \; "$sbin/snappy-lead.sh" $@
fi
