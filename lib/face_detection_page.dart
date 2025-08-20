import 'package:flutter/material.dart';
//
// class FaceDetectorPage extends StatefulWidget {
//   const FaceDetectorPage({super.key});
//
//   @override
//   State<FaceDetectorPage> createState() => _FaceDetectorPageState();
// }
//
// class _FaceDetectorPageState extends State<FaceDetectorPage> {
//   @override
//   Widget build(BuildContext context) {
//     return const Placeholder();
//   }
// }


import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
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
    final paint = Paint()
      ..color = Colors.green
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke;

    for (final face in faces) {
      final rect = Rect.fromLTWH(
        face.x * size.width,
        face.y * size.height,
        face.w * size.width,
        face.h * size.height,
      );
      canvas.drawRect(rect, paint);
    }
  }

  @override
  bool shouldRepaint(covariant FacePainter oldDelegate) =>
      oldDelegate.faces != faces;
}