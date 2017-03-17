package me.reddev.osucelebrity;

import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.DisconnectEvent;
import org.slf4j.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public abstract class AbstractIrcBot extends ListenerAdapter implements Runnable {

  private PircBotX bot;
  
  protected abstract Configuration getConfiguration() throws Exception;
  
  protected abstract Logger getLog();
  
  private final CountDownLatch connectLatch = new CountDownLatch(1);
  
  /**
   * Creates a bot instance and connects.
   */
  public void run() {
    try {
      bot = new PircBotX(getConfiguration());

      // since new pircbotx has fancy stuff with multiple servers
      // lets also do fancy with possible multiple servers
      String server = bot.getConfiguration().getServers().stream()
              .map(Configuration.ServerEntry::getHostname)
              .collect(Collectors.joining(","));
      getLog().debug("Connecting to [{}] as {}", server, bot.getConfiguration().getLogin());
      bot.startBot();
    } catch (Exception e) {
      getLog().error("IRC error", e);
    }
  }
  
  public PircBotX getBot() {
    return bot;
  }

  @Override
  public void onConnect(ConnectEvent event) throws Exception {
    connectLatch.countDown();
    getLog().debug("Connected to {} as {}", bot.getServerHostname(),
          bot.getConfiguration().getLogin());
  }

  @Override
  public void onDisconnect(DisconnectEvent event) throws Exception {
    getLog().debug("Disconnected from {}", bot.getServerHostname());
  }
  
  public void awaitConnect() throws InterruptedException {
    connectLatch.await();
  }
}
