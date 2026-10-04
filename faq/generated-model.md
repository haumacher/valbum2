# The model is generated — for both toolchains

`image-server-shared/src/main/java/.../shared/model/model.proto` is the source of truth. The
msgbuf-generator Maven plugin regenerates the Java model **and** `valbum_ui/lib/resource.dart`
on every build. Edit the `.proto`, never the output; don't reformat `resource.dart`.

Generator limitations:
- `repeated string` is mis-typed in Dart — wrap it in a message (e.g. `repeated LabelName`).
- A concrete message cannot be subclassed in Dart — add an optional field instead (e.g. `ErrorInfo.identify`).
