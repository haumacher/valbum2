# JXLatte (vendored)

The pure-Java JPEG XL decoder of Leo Izen, <https://github.com/Traneptora/jxlatte>, MIT licence
(`LICENSE`), copied unchanged from commit `b98e86eb6a13baca5967dc25cab7698ac6d14675`
(2026-08-23): `java/com/**` into `src/main/java`, `java/resources/*.icc.zz` into
`src/main/resources`. The server decodes `.jxl` pictures with it (issue #193).

Vendored because it is published on no Maven repository. To update, copy the same two folders of a
newer commit over these and name the commit here; never edit the sources in place.
