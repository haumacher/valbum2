An album as the build of 2.13.0, before issue #253, wrote it: `index.json` was produced by that
build itself (a GET of the album, the second photo rated and described, a PUT back), with the two
tiny generated JPEGs it describes. `TestBackgroundRecord#testAnOlderSidecarStillReadsWithItsParts`
reads it with a background that has not recorded the folder yet: the parts must be answered at once
and never be analysed again. Do not regenerate it with a later build.
