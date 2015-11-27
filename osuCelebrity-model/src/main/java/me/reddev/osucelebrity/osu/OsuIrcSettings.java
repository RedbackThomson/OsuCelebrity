package me.reddev.osucelebrity.osu;

public interface OsuIrcSettings {
  String getOsuIrcUsername();

  String getOsuIrcPassword();

  String getOsuPath();

  String getOsuIrcHost();

  int getOsuIrcPort();

  String getOsuCommandUser();
  
  String getOsuIrcCommand();

  String getOsuIrcAutoJoin();
  
  boolean isOsuIrcSilenced();
}
