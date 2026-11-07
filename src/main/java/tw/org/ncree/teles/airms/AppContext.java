package tw.org.ncree.teles.airms;

import java.time.Clock;
import java.util.Map;
import tw.org.ncree.teles.airms.auth.AuthService;
import tw.org.ncree.teles.airms.backup.BackupService;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.config.TlsKeystore;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.MonthlyStore;
import tw.org.ncree.teles.airms.db.Series;
import tw.org.ncree.teles.airms.feed.FeedHttpClient;
import tw.org.ncree.teles.airms.feed.FeedStatusBoard;
import tw.org.ncree.teles.airms.feed.RawXmlArchive;
import tw.org.ncree.teles.airms.repo.VdRepository;
import tw.org.ncree.teles.airms.settings.SettingsService;
import tw.org.ncree.teles.airms.storage.DataVolume;
import tw.org.ncree.teles.airms.view.LiveViewService;
import tw.org.ncree.teles.airms.view.NetworkConfig;
import tw.org.ncree.teles.airms.view.RampService;

/**
 * 啟動時組好、各元件共用的物件。
 * coreDb：帳號、設定、操作紀錄；liveDb：記憶體中的路段／RMS 最新一筆；monthly：各系列的月檔。
 */
public record AppContext(
    AppConfig config,
    Clock clock,
    DataVolume volume,
    Db coreDb,
    Db liveDb,
    Map<Series, MonthlyStore> monthly,
    VdRepository vdRepository,
    RawXmlArchive archive,
    FeedHttpClient feedHttp,
    FeedStatusBoard feedStatus,
    SettingsService settings,
    AuthService auth,
    NetworkConfig network,
    LiveViewService liveView,
    RampService ramps,
    BackupService backup,
    TlsKeystore.Cert tlsCert) {

  public static final String USER_AGENT = "AiRMS/" + MainVerticle.APP_VERSION;

  public MonthlyStore vd() {
    return monthly.get(Series.VD);
  }

  public MonthlyStore sections() {
    return monthly.get(Series.SECTION);
  }

  public MonthlyStore rms() {
    return monthly.get(Series.RMS);
  }

  public MonthlyStore feedLog() {
    return monthly.get(Series.FEEDLOG);
  }
}
