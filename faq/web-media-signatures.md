# Web video and downloads use signed URLs

A browser `<video>` element (and an `<a download>`) fetches its URL itself and cannot send the
`Authorization` header. So the app asks `?type=media-url` for a short-lived HMAC-signed address
(`MediaSignatures`, `media=` parameter). Never put the bearer token in a URL; mask `media=` in logs.
