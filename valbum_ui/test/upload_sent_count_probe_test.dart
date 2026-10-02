/// Probe of #194 the delivery did not write: photos the server already holds
/// are not counted as sent, and a file the server refuses (#186) neither
/// pushes the sent count past the total nor below what it confirmed.
library;

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';

import 'upload_sent_count_test.dart' show mb, sizedFile, namesIn;

void main() {
  test('present photos are skipped, a refused one is still a sent one',
      () async {
    var transport = MockClient.streaming((request, body) async {
      if (request.method == "POST") {
        await body.drain<void>();
        // The first five are on the server already.
        var present = [
          for (var i = 0; i < 5; i++)
            '{"hash":"hash-of-img-$i.jpg","name":"img-$i.jpg"}'
        ];
        return http.StreamedResponse(
            http.ByteStream.fromBytes(
                utf8.encode('{"present":[${present.join(",")}]}')),
            200,
            headers: const {"content-type": "application/json"});
      }
      var names = <String>[];
      await for (var chunk in body) {
        if (chunk.length < 4096) names.addAll(namesIn(latin1.decode(chunk)));
      }
      var stored = names.where((n) => n != "img-7.jpg");
      return http.StreamedResponse(
        http.ByteStream.fromBytes(utf8.encode(
          '{"files":[${stored.map((n) => '{"name":"$n","storedAs":"$n",'
              '"hash":"h-$n","status":"stored"}').join(",")}],'
          '"refused":[{"name":"img-7.jpg","reason":"No."}]}',
        )),
        200,
        headers: const {"content-type": "application/json; charset=utf-8"},
      );
    });
    var client = VAlbumClient(
        dataUrl: "http://server/valbum/data", httpClient: transport);
    var reports = <UploadProgress>[];
    var summary = await client.uploadNew(
      const ["album"],
      [for (var i = 0; i < 20; i++) sizedFile("img-$i.jpg", mb)],
      onProgress: (report) {
        if (report.phase != UploadPhase.preparing) reports.add(report);
      },
    );

    expect(summary.present, 5);
    expect(summary.stored, 14);
    expect(summary.refused.map((r) => r.name), ["img-7.jpg"]);
    var transferring = reports
        .where((r) =>
            r.phase == UploadPhase.transferring ||
            r.phase == UploadPhase.waiting)
        .toList();
    expect(transferring.map((r) => r.imagesTotal).toSet(), {15},
        reason: "only what is transferred is counted");
    for (var i = 0; i < transferring.length; i++) {
      var r = transferring[i];
      expect(r.imagesSent, lessThanOrEqualTo(r.imagesTotal));
      expect(r.imagesSent, greaterThanOrEqualTo(r.imagesDone));
      if (i > 0) {
        expect(
            r.imagesSent, greaterThanOrEqualTo(transferring[i - 1].imagesSent));
      }
    }
    expect(
        transferring.map((r) => r.imagesSent).reduce((a, b) => a > b ? a : b),
        15);
  });
}
