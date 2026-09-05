/// The single image viewer: zoom, pan, swipe and keyboard navigation.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app.dart';
import 'client.dart';
import 'resource.dart';

/// Displays a single [ImagePart] full-screen.
class ImageView extends StatelessWidget {
  final VAlbumState albumState;
  final ImagePart image;

  const ImageView(this.albumState, this.image, {super.key});

  VAlbumClient get client => albumState.client;
  String get baseUrl => albumState.baseUrl;

  @override
  Widget build(BuildContext context) {
    var self = image;
    return Scaffold(
      backgroundColor: Colors.black,
      body: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints constraints) {
          return CallbackShortcuts(
            bindings: <ShortcutActivator, VoidCallback>{
              const SingleActivator(LogicalKeyboardKey.arrowLeft): () =>
                  gotoPrevious(self),
              const SingleActivator(LogicalKeyboardKey.arrowRight): () =>
                  gotoNext(self),
              const SingleActivator(LogicalKeyboardKey.arrowUp): () {
                Navigator.pop(context);
              },
            },
            child: Focus(
              autofocus: true,
              child: createViewer(self, constraints),
            ),
          );
        },
      ),
    );
  }

  InteractiveViewer createViewer(ImagePart self, BoxConstraints constraints) {
    var tx = TransformationController();

    return InteractiveViewer(
      panEnabled: true,
      minScale: 0.25,
      maxScale: 4,
      transformationController: tx,
      onInteractionEnd: (details) {
        if (details.pointerCount > 1 || tx.value.hasScale()) {
          return;
        }
        if (details.velocity.pixelsPerSecond.distance > 50) {
          var primaryVelocity = details.velocity.pixelsPerSecond.dx;
          if (kDebugMode) {
            print(details);
          }
          if (primaryVelocity > 40) {
            gotoPrevious(self);
          }
          // Swiping to the left.
          else if (primaryVelocity < 40) {
            gotoNext(self);
          }
        }
      },

      // TODO: Support video playback
      child: Image.network(
        self.kind == ImageKind.video
            ? client.thumbnailUrl("$baseUrl/${self.name}")
            : client.originalUrl("$baseUrl/${self.name}"),
        width: constraints.maxWidth,
        height: constraints.maxHeight,
        fit: BoxFit.contain,
      ),
    );
  }

  void gotoPrevious(AbstractImage self) {
    var previous = self.previous;
    if (previous != null) {
      albumState.showImage(previous);
    }
  }

  void gotoNext(AbstractImage self) {
    var next = self.next;
    if (next != null) {
      albumState.showImage(next);
    }
  }
}

extension AlmostIdentity on Matrix4 {
  bool hasScale() {
    return (row0.x - 1).abs() + (row1.y - 1).abs() + (row2.z - 1).abs() > 0.001;
  }
}
