# Demo server

`mvn exec:java@test-server -pl :image-server` → http://localhost:9090/valbum/ (port 9090), admin
seat code `ABCD-EFGH`. Running the jar by hand needs `--contextpath valbum` and a scratch copy of the
library without `.valbum`, or no seat code is printed.
