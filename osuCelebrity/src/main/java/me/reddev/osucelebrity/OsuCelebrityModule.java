package me.reddev.osucelebrity;


import me.reddev.osucelebrity.core.AutoQueue;
import me.reddev.osucelebrity.core.Clock;
import me.reddev.osucelebrity.core.CoreSettings;
import me.reddev.osucelebrity.core.Spectator;
import me.reddev.osucelebrity.core.SpectatorImpl;
import me.reddev.osucelebrity.core.StatusWindow;
import me.reddev.osucelebrity.core.StatusWindowImpl;
import me.reddev.osucelebrity.core.SystemClock;
import me.reddev.osucelebrity.core.Trust;
import me.reddev.osucelebrity.core.TrustImpl;
import me.reddev.osucelebrity.osu.Osu;
import me.reddev.osucelebrity.osu.OsuApiImpl;
import me.reddev.osucelebrity.osu.OsuApplication;
import me.reddev.osucelebrity.osu.OsuApplication.OsuApplicationSettings;
import me.reddev.osucelebrity.osu.OsuImpl;
import me.reddev.osucelebrity.osu.OsuIrcBot;
import me.reddev.osucelebrity.osu.OsuIrcSettings;
import me.reddev.osucelebrity.osuapi.OsuApi;
import me.reddev.osucelebrity.osuapi.OsuApiSettings;
import me.reddev.osucelebrity.twitch.ObsRemote;
import me.reddev.osucelebrity.twitch.SceneSwitcher;
import me.reddev.osucelebrity.twitch.Twitch;
import me.reddev.osucelebrity.twitch.TwitchApiImpl;
import me.reddev.osucelebrity.twitch.TwitchImpl;
import me.reddev.osucelebrity.twitch.TwitchIrcBot;
import me.reddev.osucelebrity.twitch.TwitchIrcSettings;
import me.reddev.osucelebrity.twitch.TwitchWhisperBot;
import me.reddev.osucelebrity.twitch.api.Kraken;
import me.reddev.osucelebrity.twitchapi.TwitchApi;
import me.reddev.osucelebrity.twitchapi.TwitchApiSettings;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.glassfish.jersey.client.proxy.WebResourceFactory;
import org.tillerino.osuApiModel.Downloader;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Singleton;
import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManagerFactory;

public class OsuCelebrityModule extends AbstractModule {
  @Override
  protected void configure() {
    Settings settings = new Settings();

    bind(TwitchIrcSettings.class).toInstance(settings);
    bind(TwitchApiSettings.class).toInstance(settings);
    bind(OsuApiSettings.class).toInstance(settings);
    bind(OsuIrcSettings.class).toInstance(settings);
    bind(OsuApplicationSettings.class).toInstance(settings);
    bind(CoreSettings.class).toInstance(settings);
    
    Properties properties = new Properties();
    try (InputStream is = ClassLoader.getSystemResourceAsStream("persistence.properties")) {
      if (is == null) {
        throw new RuntimeException("persistence.properties not found");
      }
      properties.load(is);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    bind(PersistenceManagerFactory.class)
        .toInstance(JDOHelper.getPersistenceManagerFactory(properties, "core"));

    bind(Downloader.class).toInstance(new BlockingDownloader(settings.getOsuApiKey()));
    
    bind(Clock.class).to(SystemClock.class);
    
    bind(OsuApi.class).to(OsuApiImpl.class).in(Singleton.class);
    bind(Osu.class).to(OsuImpl.class).in(Singleton.class);
    bind(Twitch.class).to(TwitchImpl.class).in(Singleton.class);
    bind(TwitchApi.class).to(TwitchApiImpl.class).in(Singleton.class);
    bind(Trust.class).to(TrustImpl.class).in(Singleton.class);
    bind(SceneSwitcher.class).to(ObsRemote.class);

    bind(OsuIrcBot.class).in(Singleton.class);
    bind(TwitchIrcBot.class).in(Singleton.class);
    bind(TwitchWhisperBot.class).in(Singleton.class);
    bind(OsuApplication.class).in(Singleton.class);
    bind(AutoQueue.class).in(Singleton.class);
    bind(ObsRemote.class).in(Singleton.class);
    
    bind(Spectator.class).to(SpectatorImpl.class);

    bind(SpectatorImpl.class).in(Singleton.class);
    bind(TwitchApiImpl.class).in(Singleton.class);
    
    bind(ExecutorService.class).toInstance(Executors.newCachedThreadPool());
    
    StatusWindow statusWindow = new StatusWindow.DummyStatusWindow();
    try {
      statusWindow = new StatusWindowImpl(new SystemClock());
    } catch (Exception e) {
      // can fail on some testing environments
    }
    bind(StatusWindow.class).toInstance(statusWindow);
  }
  
  /**
   * Creates a Jersey client proxy for the Kraken API.
   */
  @Provides
  public Kraken createKraken(TwitchApiSettings settings) {
    return WebResourceFactory.newResource(Kraken.class,
        JerseyClientBuilder.newClient().target(settings.getTwitchApiRoot())
            .queryParam("client_id", settings.getTwitchClientId()));
  }
}
