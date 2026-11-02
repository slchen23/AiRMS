package tw.org.ncree.teles.airms;

import java.time.Clock;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.feed.FeedHttpClient;
import tw.org.ncree.teles.airms.feed.FeedStatusBoard;
import tw.org.ncree.teles.airms.feed.RawXmlArchive;
import tw.org.ncree.teles.airms.repo.VdRepository;
import tw.org.ncree.teles.airms.storage.DataVolume;

/** 啟動時組好、各元件共用的物件。 */
public record AppContext(
    AppConfig config,
    Clock clock,
    DataVolume volume,
    Db airmsDb,
    Db vdDb,
    VdRepository vdRepository,
    RawXmlArchive archive,
    FeedHttpClient feedHttp,
    FeedStatusBoard feedStatus) {

  public static final String USER_AGENT = "AiRMS/0.1";
}
