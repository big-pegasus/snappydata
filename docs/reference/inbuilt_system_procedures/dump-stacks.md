# SYS.DUMP_STACKS

Writes thread stacks, locks, and transaction states to the SnappyData log file. You can write stack information either for the current SnappyData member or for all SnappyData members in the distributed system.

<!--See also [print-stacks](../command_line_utilities/store-print-stacks.md) for information about writing thread stacks to standard out or to a specified file.--->

## Syntax

```no-highlight
SYS.DUMP_STACKS (
IN ALL BOOLEAN
)
```

**ALL**   
Specifies boolean value: **true** or **1** to log stack trace information for all SnappyData members, or **false** or **0** to log information only for the local SnappyData member.

## Example

This command writes thread stack information only for the local SnappyData member. The stack information is written to the SnappyData log file (by default snappyserver.log in the member startup directory):

```no-highlight
snappy> call sys.dump_stacks('false');
Statement executed.
```

<!-- See [print-stacks](../command_line_utilities/store-print-stacks.md) for an example of the partial thread stack output.-->


