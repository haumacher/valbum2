# A photo of a search is named by its path

The search view and a saved search (#227) answer each photo named by its path below the folder
searched (`2024/Rome/IMG_1.jpg`), not by its file name. So `<folder>/<name>` of the search view is
the photo's own address, and `<saved search>/<name>` is resolved by `ImageServlet#searchedPhoto`:
the path below the folder the saved search lies in, served while it still matches the query. Every
action of a saved search that takes names (zip, collect, crop, the sidecar `PUT`) reads them so; in
the app a route keeps such a name as one segment (`%2F` in the location).

A query is a persisted format: an unknown criterion reads as `null` and a newer `version` is
refused (`PhotoSearch#refusal`) — never evaluated narrower or wider, never overwritten.
