CsvNumbers.sum rejects otherwise valid values surrounded by spaces and mishandles an empty line. Fix the parser so whitespace is ignored and blank input sums to zero. Do not edit tests.
