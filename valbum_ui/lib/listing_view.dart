/// The folder listing view and the dialogs creating albums and folders.
library;

import 'package:date_field/date_field.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'app.dart';
import 'client.dart';
import 'resource.dart';

/// Displays a [ListingInfo] as a grid of folder tiles.
class ListingView extends StatelessWidget {
  final VAlbumState albumState;
  final ListingInfo listing;

  const ListingView(this.albumState, this.listing, {super.key});

  VAlbumClient get client => albumState.client;
  String get baseUrl => albumState.baseUrl;

  @override
  Widget build(BuildContext context) {
    var self = listing;
    return Scaffold(
      appBar: AppBar(
        title: Text(self.title),
        actions: <Widget>[
          menu(context, [
            menuItem(Icons.create_new_folder, 'Create album', createAlbum),
            menuItem(
              Icons.create_new_folder_outlined,
              'Create folder',
              createFolder,
            ),
            menuItem(Icons.update, "Reload", (_) => albumState.reload()),
          ]),
        ],
      ),
      body: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints constraints) {
          double imageBorder = 8;
          var preferredImageWidth = 200;
          var maxWidth = constraints.maxWidth;
          double preferredImageSpace = preferredImageWidth + 2 * imageBorder;
          double imagesPerRowFrag = maxWidth / preferredImageSpace;
          var imagesPerRow = imagesPerRowFrag.round();
          bool underflow = self.folders.length < imagesPerRow;
          double difference =
              underflow ? 0 : maxWidth - imagesPerRow * preferredImageSpace;
          double imageSpace = preferredImageSpace + difference / imagesPerRow;

          return SingleChildScrollView(
            scrollDirection: Axis.vertical,
            child: buildFolderList(
              self,
              imageSpace - 2 * imageBorder,
              imageBorder,
            ),
          );
        },
      ),
    );
  }

  Wrap buildFolderList(
    ListingInfo self,
    double imageWidth,
    double imageBorder,
  ) {
    return Wrap(
      children: self.folders.map((folder) {
        return Padding(
          padding: EdgeInsets.all(imageBorder),
          child: GestureDetector(
            onTap: () => albumState.showElement(folder.name),
            child: SizedBox(
              width: imageWidth,
              child: Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: buildFolderWidget(folder, imageWidth),
                  ),
                  Text(
                    folder.title,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                    textAlign: TextAlign.center,
                  ),
                  Text(folder.subTitle, textAlign: TextAlign.center),
                ],
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget buildFolderWidget(FolderInfo folder, double width) {
    var indexPicture = folder.indexPicture;
    if (indexPicture == null) {
      return Container(
        width: width,
        height: width,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(5),
          border: Border.all(color: Colors.blue, width: 3),
        ),
        child: Center(
          child: Icon(Icons.folder, size: width / 2, color: Colors.blue),
        ),
      );
    }

    return Image.network(
      client.thumbnailUrl("$baseUrl/${folder.name}/${indexPicture.image}"),
      width: width,
      height: width,
      fit: BoxFit.cover,
    );
  }

  void createFolder(BuildContext context) async {
    ListingInfo? folder = await showGeneralDialog(
      context: context,
      pageBuilder: (context, _, __) => const CreateFolderDialog(),
    );

    if (folder == null) {
      return;
    }

    await client.putResource("$baseUrl/${folder.path}", folder);

    albumState.reload();
    albumState.showElement(folder.path);
  }

  void createAlbum(BuildContext context) async {
    AlbumInfo? album = await showGeneralDialog(
      context: context,
      pageBuilder: (context, _, __) => const CreateAlbumDialog(),
    );

    if (album == null) {
      return;
    }

    await client.putResource("$baseUrl/${album.path}", album);

    albumState.reload();
    albumState.showElement(album.path);
  }
}

class CreateAlbumDialog extends StatefulWidget {
  const CreateAlbumDialog({super.key});

  @override
  State<StatefulWidget> createState() => CreateAlbumDialogState();
}

class CreateAlbumDialogState extends State<CreateAlbumDialog> {
  var formKey = GlobalKey<FormState>();
  String? albumTitle;
  String? albumSubTitle;
  DateTime? albumDate;

  @override
  Widget build(BuildContext context) {
    var now = DateTime.now();

    return Dialog(
      child: Form(
        key: formKey,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              DefaultTextStyle(
                style: DialogTheme.of(context).titleTextStyle ??
                    Theme.of(context).textTheme.titleLarge!,
                child: Semantics(
                  // For iOS platform, the focus always lands on the title.
                  // Set nameRoute to false to avoid title being announced twice.
                  namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                  container: true,
                  child: const Text("Neues Album"),
                ),
              ),
              DateTimeFormField(
                mode: DateTimeFieldPickerMode.date,
                firstDate: DateTime(1900),
                lastDate: now,
                initialDate: now,
                onSaved: (value) => albumDate = value,
                dateFormat: DateFormat("yyyy-MM-dd"),
                validator: (value) {
                  return value == null ? "Muss angegeben werden." : null;
                },
                decoration: const InputDecoration(
                  label: Text("Datum"),
                  suffixIcon: Icon(Icons.date_range),
                ),
              ),
              TextFormField(
                decoration: const InputDecoration(label: Text("Titel")),
                onSaved: (value) => albumTitle = value,
                validator: (String? value) {
                  return value == null || value.isEmpty
                      ? "Darf nicht leer sein"
                      : null;
                },
              ),
              TextFormField(
                decoration: const InputDecoration(label: Text("Untertitel")),
                onSaved: (value) => albumSubTitle = value,
              ),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Anlegen"),
                      onPressed: createPressed,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void createPressed() {
    var formState = formKey.currentState;
    if (!formState!.validate()) {
      return;
    }

    formState.save();

    var title = albumTitle!;
    var date = albumDate;

    var info = AlbumInfo(
      title: title,
      subTitle: albumSubTitle ?? "",
      path:
          (date != null ? DateFormat("yyyy-MM-dd ").format(date) : "") + title,
    );

    Navigator.of(context).pop(info);
  }
}

class CreateFolderDialog extends StatefulWidget {
  const CreateFolderDialog({super.key});

  @override
  State<StatefulWidget> createState() => CreateFolderDialogState();
}

class CreateFolderDialogState extends State<CreateFolderDialog> {
  var formKey = GlobalKey<FormState>();
  String? folderName;

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: Form(
        key: formKey,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              DefaultTextStyle(
                style: DialogTheme.of(context).titleTextStyle ??
                    Theme.of(context).textTheme.titleLarge!,
                child: Semantics(
                  // For iOS platform, the focus always lands on the title.
                  // Set nameRoute to false to avoid title being announced twice.
                  namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                  container: true,
                  child: const Text("Neuer Ordner"),
                ),
              ),
              TextFormField(
                decoration: const InputDecoration(label: Text("Name")),
                onSaved: (value) => folderName = value,
                validator: (String? value) {
                  return value == null || value.isEmpty
                      ? "Darf nicht leer sein"
                      : null;
                },
              ),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Anlegen"),
                      onPressed: createPressed,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void createPressed() {
    var formState = formKey.currentState;
    if (!formState!.validate()) {
      return;
    }

    formState.save();

    var name = folderName!;

    var info = ListingInfo(title: name, path: name);

    Navigator.of(context).pop(info);
  }
}
