package me.reddev.osucelebrity;

import com.google.inject.Guice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.reddev.osucelebrity.core.AutoQueue;
import me.reddev.osucelebrity.core.CoreSettings;
import me.reddev.osucelebrity.core.SpectatorImpl;
import me.reddev.osucelebrity.core.StatusWindow;
import me.reddev.osucelebrity.core.StatusWindowImpl;
import me.reddev.osucelebrity.core.api.CoreApiApplication;
import me.reddev.osucelebrity.osu.OsuActivityUpdater;
import me.reddev.osucelebrity.osu.OsuApplication;
import me.reddev.osucelebrity.osu.OsuIrcBot;
import me.reddev.osucelebrity.twitch.SceneSwitcher;
import me.reddev.osucelebrity.twitch.TwitchApiImpl;
import me.reddev.osucelebrity.twitch.TwitchIrcBot;
import me.reddev.osucelebrity.twitch.TwitchWhisperBot;
import org.apache.commons.lang3.ArrayUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.glassfish.jersey.jetty.JettyHttpContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.ws.rs.core.UriBuilder;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class OsuCelebrity {
  final SpectatorImpl spectator;
  final TwitchIrcBot twitchBot;
  final TwitchWhisperBot twitchWhisper;
  final OsuIrcBot osuBot;
  final CoreApiApplication apiServerApp;
  final CoreSettings coreSettings;
  final OsuApplication osuApp;
  final ScheduledExecutorService exec = Executors.newScheduledThreadPool(16);
  final OsuActivityUpdater osuActivityUpdater;
  final TwitchApiImpl twitchApi;
  final Settings settings;
  final StatusWindow statusWindow;
  final AutoQueue autoQueue;
  final SceneSwitcher obsRemote;
  
  boolean startFrozen;
  
  void start() throws Exception {
    MBeanServer jmxServer = ManagementFactory.getPlatformMBeanServer();
    jmxServer.registerMBean(settings,
        new ObjectName("osuCeleb:type=Settings"));
    jmxServer.registerMBean(spectator,
        new ObjectName("osuCeleb:type=Spectator"));

    exec.scheduleWithFixedDelay(obsRemote::connect, 0, 1, TimeUnit.SECONDS);
    
    log.debug("Connecting to OBS");
    obsRemote.awaitConnect();
    
    if (startFrozen) {
      spectator.setFrozen(true);
    }
    
    exec.scheduleAtFixedRate(spectator::loop, 0, 100, TimeUnit.MILLISECONDS);
    exec.submit(osuBot);
    exec.submit(twitchBot);
    exec.scheduleWithFixedDelay(twitchWhisper, 0, 5, TimeUnit.SECONDS);
    exec.scheduleAtFixedRate(osuApp::updateWindowTitle, 0, 100, TimeUnit.MILLISECONDS);
    exec.scheduleWithFixedDelay(osuActivityUpdater::update, 0, 5, TimeUnit.SECONDS);
    exec.scheduleWithFixedDelay(autoQueue::loop, 0, 1, TimeUnit.SECONDS);
    exec.scheduleWithFixedDelay(osuApp::updateOsuDb, 0, 10, TimeUnit.MINUTES);

    URI baseUri = UriBuilder.fromUri("http://localhost/").port(coreSettings.getApiPort()).build();

    Server apiServer =
        JettyHttpContainerFactory
            .createServer(baseUri, ResourceConfig.forApplication(apiServerApp), false);
    {
      HandlerList handlers = new HandlerList();
      ResourceHandler resourceHandler = new ResourceHandler();
      resourceHandler.setResourceBase("html");
      
      handlers.addHandler(resourceHandler);
      handlers.addHandler(apiServer.getHandler());
      
      apiServer.setHandler(handlers);
    }

    apiServer.start();
    
    ((StatusWindowImpl) statusWindow).setVisible(true);
  }

  /**
   * Starts the osuCelebrity bot.
   * 
   * @param args Command line arguments 
   */
  public static void main(String[] args) throws Exception {
    OsuCelebrity osuCelebrity =
        Guice.createInjector(new OsuCelebrityModule()).getInstance(OsuCelebrity.class);
    if (ArrayUtils.contains(args, "-freeze")) {
      osuCelebrity.startFrozen = true;
    }

    osuCelebrity.start();
  }
}
