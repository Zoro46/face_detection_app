import 'package:flutter/material.dart';
import 'dart:async';
import 'dart:convert';
import 'package:flutter/services.dart';


class FaceBox {
  final double x, y, w, h;
  FaceBox({required this.x, required this.y, required this.w, required this.h});

  factory FaceBox.fromJson(Map<String, dynamic> json) {
    return FaceBox(
      x: (json['x'] as num).toDouble(),
      y: (json['y'] as num).toDouble(),
      w: (json['w'] as num).toDouble(),
      h: (json['h'] as num).toDouble(),
    );
  }
}

class FaceDetectorPage extends StatefulWidget {
  @override
  State<FaceDetectorPage> createState() => _FaceDetectorPageState();
}

class _FaceDetectorPageState extends State<FaceDetectorPage> {
  static const EventChannel _eventChannel = EventChannel('mediapipe_faces');
  StreamSubscription<dynamic>? _faceSubscription;

  List<FaceBox> _faces = [];

  @override
  void initState() {
    super.initState();

    _faceSubscription = _eventChannel.receiveBroadcastStream().listen((event) {
      final decoded = jsonDecode(event as String) as Map<String, dynamic>;
      final boxes = decoded['boxes'] as List;
      setState(() {
        _faces = boxes.map((e) => FaceBox.fromJson(e)).toList();
      });
    });
  }

  @override
  void dispose() {
    _faceSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(
            child: AndroidView(
              viewType: 'camera_preview',
              layoutDirection: TextDirection.ltr,
            ),
          ),
          Positioned.fill(
            child: RepaintBoundary(
              child: CustomPaint(painter: FacePainter(_faces)),
            ),
          ),
        ],
      ),
    );
  }
}

class FacePainter extends CustomPainter {
  final List<FaceBox> faces;

  FacePainter(this.faces);

  @override
  void paint(Canvas canvas, Size size) {

    // for (final face in faces) {
    //   final rect = Rect.fromLTWH(
    //     face.x * size.width,
    //     face.y * size.height,
    //     face.w * size.width,
    //     face.h * size.height,
    //   );
    //   canvas.drawRRect(RRect.fromRectAndRadius(rect, Radius.circular(12)), paint);
    // }

    for (final face in faces) {
      final rect = Rect.fromLTWH(
        face.x * size.width,
        face.y * size.height,
        face.w * size.width,
        face.h * size.height,
      );

      // Style
      final double len = 20.0;         // length of each corner segment

      final cornerPaint = Paint()
        ..color = Colors.green
        ..strokeWidth = 4.0
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round; // rounded ends look nicer

      // Convenience points
      final tl = rect.topLeft;
      final tr = rect.topRight;
      final bl = rect.bottomLeft;
      final br = rect.bottomRight;

      // Top-left
      canvas.drawLine(tl, tl.translate(len, 0), cornerPaint); // →
      canvas.drawLine(tl, tl.translate(0, len), cornerPaint); // ↓

      // Top-right
      canvas.drawLine(tr, tr.translate(-len, 0), cornerPaint); // ←
      canvas.drawLine(tr, tr.translate(0, len), cornerPaint);  // ↓

      // Bottom-left
      canvas.drawLine(bl, bl.translate(len, 0), cornerPaint);  // →
      canvas.drawLine(bl, bl.translate(0, -len), cornerPaint); // ↑

      // Bottom-right
      canvas.drawLine(br, br.translate(-len, 0), cornerPaint); // ←
      canvas.drawLine(br, br.translate(0, -len), cornerPaint); // ↑
    }
  }

  @override
  bool shouldRepaint(covariant FacePainter oldDelegate) =>
      oldDelegate.faces != faces;
}