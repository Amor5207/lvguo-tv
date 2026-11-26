class UserProfile {
  final String uid;
  final String name;
  final String avatar;
  final String cover;
  final String signature;
  final bool vip;
  final int watchMs;
  final int activeDays;

  const UserProfile({
    required this.uid,
    required this.name,
    required this.avatar,
    required this.cover,
    required this.signature,
    required this.vip,
    required this.watchMs,
    required this.activeDays,
  });

  factory UserProfile.fromJson(Map<String, dynamic> j) => UserProfile(
        uid: j['uid']?.toString() ?? '',
        name: j['name']?.toString() ?? '红果用户',
        avatar: j['avatar']?.toString() ?? '',
        cover: j['cover']?.toString() ?? '',
        signature: j['signature']?.toString() ?? '',
        vip: j['vip'] == true,
        watchMs: (j['watchMs'] as num?)?.toInt() ?? 0,
        activeDays: (j['activeDays'] as num?)?.toInt() ?? 0,
      );
}

class SeriesItem {
  final String id;
  final String title;
  final String cover;
  final int episodes;
  final int plays;
  final String intro;
  final String category;
  final String firstVid;
  final int rank;

  const SeriesItem({
    required this.id,
    required this.title,
    required this.cover,
    required this.episodes,
    required this.plays,
    required this.intro,
    required this.category,
    required this.firstVid,
    required this.rank,
  });

  factory SeriesItem.fromJson(Map<String, dynamic> j) => SeriesItem(
        id: (j['id'] ?? j['seriesId'] ?? '').toString(),
        title: j['title']?.toString() ?? '',
        cover: j['cover']?.toString() ?? '',
        episodes: ((j['episodes'] ?? j['episodeCount']) as num?)?.toInt() ?? 0,
        plays: (j['plays'] as num?)?.toInt() ?? 0,
        intro: j['intro']?.toString() ?? '',
        category: j['category']?.toString() ?? '',
        firstVid: j['firstVid']?.toString() ?? '',
        rank: (j['rank'] as num?)?.toInt() ?? 0,
      );
}

class EpisodeItem {
  final int index;
  final String title;
  final String vid;
  final int duration;
  final String cover;

  const EpisodeItem({
    required this.index,
    required this.title,
    required this.vid,
    required this.duration,
    required this.cover,
  });

  factory EpisodeItem.fromJson(Map<String, dynamic> j) => EpisodeItem(
        index: (j['index'] as num?)?.toInt() ?? 1,
        title: j['title']?.toString() ?? '',
        vid: j['vid']?.toString() ?? '',
        duration: (j['duration'] as num?)?.toInt() ?? 0,
        cover: j['cover']?.toString() ?? '',
      );
}

class DetailData {
  final String id;
  final String title;
  final String cover;
  final String intro;
  final int count;
  final String rating;
  final String heat;
  final String tag;
  final int plays;
  final List<String> actors;
  final List<EpisodeItem> episodes;
  final bool multiSeason;
  final int seasonIndex;

  const DetailData({
    required this.id,
    required this.title,
    required this.cover,
    required this.intro,
    required this.count,
    required this.rating,
    required this.heat,
    required this.tag,
    required this.plays,
    required this.actors,
    required this.episodes,
    required this.multiSeason,
    required this.seasonIndex,
  });

  factory DetailData.fromJson(Map<String, dynamic> j) => DetailData(
        id: j['id']?.toString() ?? '',
        title: j['title']?.toString() ?? '',
        cover: j['cover']?.toString() ?? '',
        intro: j['intro']?.toString() ?? '',
        count: (j['count'] as num?)?.toInt() ?? 0,
        rating: j['rating']?.toString() ?? '',
        heat: j['heat']?.toString() ?? '',
        tag: j['tag']?.toString() ?? '',
        plays: (j['plays'] as num?)?.toInt() ?? 0,
        actors: (j['actors'] as List<dynamic>? ?? const [])
            .map((e) => e.toString())
            .toList(),
        episodes: (j['episodes'] as List<dynamic>? ?? const [])
            .map((e) => EpisodeItem.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        multiSeason: j['multiSeason'] == true,
        seasonIndex: (j['seasonIndex'] as num?)?.toInt() ?? 0,
      );
}

class SeasonItem {
  final String id;
  final String title;
  final String cover;
  final int number;
  final int episodes;
  final bool current;

  const SeasonItem({
    required this.id,
    required this.title,
    required this.cover,
    required this.number,
    required this.episodes,
    required this.current,
  });

  factory SeasonItem.fromJson(Map<String, dynamic> j) => SeasonItem(
        id: j['id']?.toString() ?? '',
        title: j['title']?.toString() ?? '',
        cover: j['cover']?.toString() ?? '',
        number: (j['number'] as num?)?.toInt() ?? 0,
        episodes: (j['episodes'] as num?)?.toInt() ?? 0,
        current: j['current'] == true,
      );
}

class GearItem {
  final String definition;
  final String codec;
  final int width;
  final int height;
  final int size;

  const GearItem({
    required this.definition,
    required this.codec,
    required this.width,
    required this.height,
    required this.size,
  });

  factory GearItem.fromJson(Map<String, dynamic> j) => GearItem(
        definition: j['definition']?.toString() ?? '',
        codec: j['codec']?.toString() ?? '',
        width: (j['width'] as num?)?.toInt() ?? 0,
        height: (j['height'] as num?)?.toInt() ?? 0,
        size: (j['size'] as num?)?.toInt() ?? 0,
      );
}

class PlayResult {
  final List<GearItem> gears;
  final String definition;
  final int width;
  final int height;
  final String mediaUrl;
  final String? localFile;

  const PlayResult({
    required this.gears,
    required this.definition,
    required this.width,
    required this.height,
    required this.mediaUrl,
    required this.localFile,
  });

  factory PlayResult.fromJson(Map<String, dynamic> j) => PlayResult(
        gears: (j['gears'] as List<dynamic>? ?? const [])
            .map((e) => GearItem.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        definition: j['definition']?.toString() ?? '',
        width: (j['width'] as num?)?.toInt() ?? 0,
        height: (j['height'] as num?)?.toInt() ?? 0,
        mediaUrl: j['mediaUrl']?.toString() ?? '',
        localFile: j['localFile']?.toString(),
      );
}

class FeedChannel {
  final String id;
  final String label;

  const FeedChannel({required this.id, required this.label});

  factory FeedChannel.fromJson(Map<String, dynamic> j) => FeedChannel(
        id: j['id']?.toString() ?? '',
        label: j['label']?.toString() ?? '',
      );
}

class HistoryEntry {
  final String seriesId;
  final String title;
  final String cover;
  final String vid;
  final int episodeIndex;
  final int episodeCount;
  final int positionMs;
  final int time;

  const HistoryEntry({
    required this.seriesId,
    required this.title,
    required this.cover,
    required this.vid,
    required this.episodeIndex,
    required this.episodeCount,
    required this.positionMs,
    required this.time,
  });

  factory HistoryEntry.fromJson(Map<String, dynamic> j) => HistoryEntry(
        seriesId: j['seriesId']?.toString() ?? '',
        title: j['title']?.toString() ?? '',
        cover: j['cover']?.toString() ?? '',
        vid: j['vid']?.toString() ?? '',
        episodeIndex: (j['episodeIndex'] as num?)?.toInt() ?? 0,
        episodeCount: (j['episodeCount'] as num?)?.toInt() ?? 0,
        positionMs: (j['positionMs'] as num?)?.toInt() ?? 0,
        time: (j['time'] as num?)?.toInt() ?? 0,
      );
}

class ReservationItem {
  final String seriesId;
  final String title;
  final String cover;
  final bool online;
  final String desc;
  final int episodeCount;
  final String publishText;
  final int time;

  const ReservationItem({
    required this.seriesId,
    required this.title,
    required this.cover,
    required this.online,
    required this.desc,
    required this.episodeCount,
    required this.publishText,
    required this.time,
  });

  factory ReservationItem.fromJson(Map<String, dynamic> j) => ReservationItem(
        seriesId: j['seriesId']?.toString() ?? '',
        title: j['title']?.toString() ?? '',
        cover: j['cover']?.toString() ?? '',
        online: j['online'] == true,
        desc: j['desc']?.toString() ?? '',
        episodeCount: (j['episodeCount'] as num?)?.toInt() ?? 0,
        publishText: j['publishText']?.toString() ?? '',
        time: (j['time'] as num?)?.toInt() ?? 0,
      );
}

class CatOption {
  final String id;
  final String label;

  const CatOption({required this.id, required this.label});

  factory CatOption.fromJson(Map<String, dynamic> j) => CatOption(
        id: j['id']?.toString() ?? '',
        label: j['label']?.toString() ?? '',
      );
}

class CatGroup {
  final String name;
  final List<CatOption> items;

  const CatGroup({required this.name, required this.items});

  factory CatGroup.fromJson(Map<String, dynamic> j) => CatGroup(
        name: j['name']?.toString() ?? '',
        items: (j['items'] as List<dynamic>? ?? const [])
            .map((e) => CatOption.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
}

class CatBoard {
  final String id;
  final String label;
  final List<CatGroup> groups;

  const CatBoard({required this.id, required this.label, required this.groups});

  factory CatBoard.fromJson(Map<String, dynamic> j) => CatBoard(
        id: j['id']?.toString() ?? '',
        label: j['label']?.toString() ?? '',
        groups: (j['groups'] as List<dynamic>? ?? const [])
            .map((e) => CatGroup.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
}

class DownloadTaskItem {
  final String id;
  final String seriesId;
  final String seriesTitle;
  final String cover;
  final String vid;
  final int episodeIndex;
  final String episodeTitle;
  final String definition;
  final int totalBytes;
  final int downloadedBytes;
  final int speedBytesPerSec;
  final int chunkCount;
  final String status;
  final String savePath;
  final String error;

  const DownloadTaskItem({
    required this.id,
    required this.seriesId,
    required this.seriesTitle,
    required this.cover,
    required this.vid,
    required this.episodeIndex,
    required this.episodeTitle,
    required this.definition,
    required this.totalBytes,
    required this.downloadedBytes,
    required this.speedBytesPerSec,
    required this.chunkCount,
    required this.status,
    required this.savePath,
    required this.error,
  });

  double get progress =>
      totalBytes > 0 ? (downloadedBytes / totalBytes).clamp(0.0, 1.0) : 0.0;

  factory DownloadTaskItem.fromJson(Map<String, dynamic> j) => DownloadTaskItem(
        id: j['id']?.toString() ?? '',
        seriesId: j['seriesId']?.toString() ?? '',
        seriesTitle: j['seriesTitle']?.toString() ?? '',
        cover: j['cover']?.toString() ?? '',
        vid: j['vid']?.toString() ?? '',
        episodeIndex: (j['episodeIndex'] as num?)?.toInt() ?? 1,
        episodeTitle: j['episodeTitle']?.toString() ?? '',
        definition: j['definition']?.toString() ?? '',
        totalBytes: (j['totalBytes'] as num?)?.toInt() ?? 0,
        downloadedBytes: (j['downloadedBytes'] as num?)?.toInt() ?? 0,
        speedBytesPerSec: (j['speedBytesPerSec'] as num?)?.toInt() ?? 0,
        chunkCount: (j['chunkCount'] as num?)?.toInt() ??
            (j['chunks'] as List<dynamic>?)?.length ??
            0,
        status: j['status']?.toString() ?? 'queued',
        savePath: j['savePath']?.toString() ?? '',
        error: j['error']?.toString() ?? '',
      );
}

class DownloadSettingsData {
  final String downloadDir;
  final int maxConcurrent;
  final int threadsPerTask;
  final int speedLimitKbps;
  final String preferredQuality;
  final int autoRetryCount;
  final bool createSeriesSubdir;
  final bool notifyOnComplete;
  final bool autoResumeOnStart;

  const DownloadSettingsData({
    required this.downloadDir,
    required this.maxConcurrent,
    required this.threadsPerTask,
    required this.speedLimitKbps,
    required this.preferredQuality,
    required this.autoRetryCount,
    required this.createSeriesSubdir,
    required this.notifyOnComplete,
    required this.autoResumeOnStart,
  });

  factory DownloadSettingsData.fromJson(Map<String, dynamic> j) =>
      DownloadSettingsData(
        downloadDir: j['downloadDir']?.toString() ?? '',
        maxConcurrent: (j['maxConcurrent'] as num?)?.toInt() ?? 3,
        threadsPerTask: (j['threadsPerTask'] as num?)?.toInt() ?? 8,
        speedLimitKbps: (j['speedLimitKbps'] as num?)?.toInt() ?? 0,
        preferredQuality: j['preferredQuality']?.toString() ?? '1080p',
        autoRetryCount: (j['autoRetryCount'] as num?)?.toInt() ?? 3,
        createSeriesSubdir: j['createSeriesSubdir'] != false,
        notifyOnComplete: j['notifyOnComplete'] != false,
        autoResumeOnStart: j['autoResumeOnStart'] != false,
      );

  Map<String, dynamic> toJson() => {
        'downloadDir': downloadDir,
        'maxConcurrent': maxConcurrent,
        'threadsPerTask': threadsPerTask,
        'speedLimitKbps': speedLimitKbps,
        'preferredQuality': preferredQuality,
        'autoRetryCount': autoRetryCount,
        'createSeriesSubdir': createSeriesSubdir,
        'notifyOnComplete': notifyOnComplete,
        'autoResumeOnStart': autoResumeOnStart,
      };
}

class HotUpdateStatusData {
  final String configUrl;
  final int configRevision;
  final List<String> routes;
  final String moduleManifestUrl;
  final int activeModuleRevision;
  final String activeModulePath;
  final int latestModuleRevision;
  final String latestAppVersion;
  final String latestAppUrl;
  final String latestAppChangelog;
  final int lastCheckAt;
  final String lastError;

  const HotUpdateStatusData({
    required this.configUrl,
    required this.configRevision,
    required this.routes,
    required this.moduleManifestUrl,
    required this.activeModuleRevision,
    required this.activeModulePath,
    required this.latestModuleRevision,
    required this.latestAppVersion,
    required this.latestAppUrl,
    required this.latestAppChangelog,
    required this.lastCheckAt,
    required this.lastError,
  });

  factory HotUpdateStatusData.fromJson(Map<String, dynamic> j) =>
      HotUpdateStatusData(
        configUrl: j['configUrl']?.toString() ?? '',
        configRevision: (j['configRevision'] as num?)?.toInt() ?? 0,
        routes: (j['routes'] as List<dynamic>? ?? const [])
            .map((e) => e.toString())
            .toList(),
        moduleManifestUrl: j['moduleManifestUrl']?.toString() ?? '',
        activeModuleRevision: (j['activeModuleRevision'] as num?)?.toInt() ?? 0,
        activeModulePath: j['activeModulePath']?.toString() ?? '',
        latestModuleRevision: (j['latestModuleRevision'] as num?)?.toInt() ?? 0,
        latestAppVersion: j['latestAppVersion']?.toString() ?? '',
        latestAppUrl: j['latestAppUrl']?.toString() ?? '',
        latestAppChangelog: j['latestAppChangelog']?.toString() ?? '',
        lastCheckAt: (j['lastCheckAt'] as num?)?.toInt() ?? 0,
        lastError: j['lastError']?.toString() ?? '',
      );
}

class CommentItem {
  final String id;
  final String user;
  final String avatar;
  final String content;
  final int likes;
  final String timeText;

  const CommentItem({
    required this.id,
    required this.user,
    required this.avatar,
    required this.content,
    required this.likes,
    required this.timeText,
  });

  factory CommentItem.fromJson(Map<String, dynamic> j) => CommentItem(
        id: (j['id'] ?? j['commentId'] ?? '').toString(),
        user: (j['user'] ?? j['userName'] ?? j['author'] ?? '观众').toString(),
        avatar: j['avatar']?.toString() ?? '',
        content: (j['content'] ?? j['text'] ?? '').toString(),
        likes: ((j['likes'] ?? j['diggCount']) as num?)?.toInt() ?? 0,
        timeText: (j['timeText'] ?? j['time'] ?? '').toString(),
      );
}

String formatBytes(int bytes) {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  double v = bytes.toDouble();
  int idx = 0;
  while (v >= 1024 && idx < units.length - 1) {
    v /= 1024;
    idx++;
  }
  return '${v.toStringAsFixed(idx == 0 ? 0 : 1)} ${units[idx]}';
}

String formatPlays(int n) {
  if (n <= 0) return '';
  if (n >= 100000000) return '${(n / 100000000).toStringAsFixed(1)}亿次播放';
  if (n >= 10000) return '${(n / 10000).toStringAsFixed(1)}万次播放';
  return '$n次播放';
}
