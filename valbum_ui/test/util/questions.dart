/// Requests that ask the server and change nothing.
library;

import 'package:http/http.dart' as http;

/// Whether [request] asks what a delete or a purge would do and does nothing
/// (`?action=delete-check`, `?action=purge-check`, issue #307): the question
/// the app asks before a delete, which is no write.
bool isQuestion(http.Request request) =>
    request.url.queryParameters["action"]?.endsWith("-check") ?? false;
