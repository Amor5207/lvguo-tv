import 'package:flutter/material.dart';

/// 1:1 emoji code table matching `web/ui/shared/core.js` (`EMOJI`, 53 WebP stickers).
const Map<String, int> kLvguoEmojiMap = <String, int>{
  '微笑': 1,
  '偷笑': 2,
  '笑': 3,
  '什么': 4,
  '害羞': 5,
  '爱慕': 6,
  '飞吻': 7,
  '奸笑': 8,
  '尬笑': 9,
  '思考': 10,
  '撇嘴': 11,
  '做鬼脸': 12,
  '酷': 13,
  '翻白眼': 14,
  '惊呆': 15,
  '震惊': 16,
  '送心': 17,
  '委屈': 18,
  '快哭了': 19,
  '笑哭': 20,
  '哭': 21,
  '大笑': 22,
  '舔屏': 23,
  '怒': 24,
  '捂脸': 25,
  '吐': 26,
  '恐惧': 27,
  '抓狂': 28,
  '敬礼': 29,
  '石化': 30,
  'OK': 31,
  '赞': 32,
  '爱心': 33,
  '伤心': 34,
  'KISS': 35,
  '懂了': 36,
  '探究': 37,
  '重拳出击': 38,
  '吃瓜': 39,
  '盯': 40,
  '你细品': 41,
  '赶稿中': 42,
  '注意': 43,
  '饱了': 44,
  '码住': 45,
  '学会了': 46,
  '顶帖': 47,
  '求爆更': 48,
  '求关注': 49,
  '爽': 50,
  '鼓掌': 51,
  '没看够': 52,
  '送花': 53,
};

final RegExp _emojiTokenRe = RegExp(r'\[([^\[\]]{1,8})\]');

List<InlineSpan> buildEmojiSpans(String text, TextStyle style) {
  final matches = _emojiTokenRe.allMatches(text);
  if (matches.isEmpty) {
    return [TextSpan(text: text, style: style)];
  }
  final iconSize = (style.fontSize ?? 14.0) * 1.25;
  final spans = <InlineSpan>[];
  int cursor = 0;

  for (final m in matches) {
    final name = m.group(1);
    final id = name == null ? null : kLvguoEmojiMap[name];
    if (id == null) continue;
    if (m.start > cursor) {
      spans.add(TextSpan(text: text.substring(cursor, m.start), style: style));
    }
    spans.add(
      WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 1.5),
          child: Image.asset(
            'assets/emoji/e$id.webp',
            width: iconSize,
            height: iconSize,
            fit: BoxFit.contain,
            gaplessPlayback: true,
          ),
        ),
      ),
    );
    cursor = m.end;
  }

  if (cursor < text.length) {
    spans.add(TextSpan(text: text.substring(cursor), style: style));
  }
  if (spans.isEmpty) {
    return [TextSpan(text: text, style: style)];
  }
  return spans;
}

class EmojiText extends StatelessWidget {
  final String text;
  final TextStyle style;
  final int? maxLines;
  final TextOverflow overflow;

  const EmojiText(
    this.text, {
    super.key,
    required this.style,
    this.maxLines,
    this.overflow = TextOverflow.clip,
  });

  @override
  Widget build(BuildContext context) {
    if (!text.contains('[')) {
      return Text(
        text,
        style: style,
        maxLines: maxLines,
        overflow: overflow,
      );
    }
    return Text.rich(
      TextSpan(children: buildEmojiSpans(text, style)),
      maxLines: maxLines,
      overflow: overflow,
    );
  }
}
