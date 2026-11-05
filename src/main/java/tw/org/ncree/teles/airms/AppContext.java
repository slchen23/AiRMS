package tw.org.ncree.teles.airms;

import java.time.Clock;
import tw.org.ncree.teles.airms.auth.AuthService;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.feed.FeedHttpClient;
import tw.org.ncree.teles.airms.feed.FeedStatusBoard;
import tw.org.ncree.teles.airms.feed.RawXmlArchive;
import tw.org.ncree.teles.airms.repo.VdRepository;
import tw.org.ncree.teles.airms.settings.SettingsService;
import tw.org.ncree.teles.airms.storage.DataVolume;
import tw.org.ncree.teles.airms.view.LiveViewService;
import tw.org.ncree.teles.airms.view.NetworkConfig;
import tw.org.ncree.teles.airms.view.RampService;

/** 啟動時組好、各元件共用的物件。 */
public record AppContext(
    AppConfig config,
    Clock clock,
    DataVolume volume,
    Db airmsDb,
    Db vdDb,
    Db authDb,
    VdRepository vdRepository,
    RawXmlArchive archive,
    FeedHttpClient feedHttp,
    FeedStatusBoard feedStatus,
    SettingsService settings,
    AuthService auth,
    NetworkConfig network,
    LiveViewService liveView,
    RampService ramps) {

  public static final String USER_AGENT = "AiRMS/0.3";
}
