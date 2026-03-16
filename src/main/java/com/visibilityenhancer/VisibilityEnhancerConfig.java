package com.visibilityenhancer;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("visibilityenhancer")
public interface VisibilityEnhancerConfig extends Config
{
	@ConfigSection(name = "Self Settings", description = "Your character settings", position = 1)
	String selfSection = "selfSection";

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "selfOpacity", name = "My Opacity", position = 2, section = selfSection, description = "Transparency of your character")
	default int selfOpacity() { return 100; }

	@ConfigItem(keyName = "selfOutline", name = "Outline Myself", position = 3, section = selfSection, description = "Enable outline for yourself")
	default boolean selfOutline() { return false; }

	@Alpha
	@ConfigItem(keyName = "selfOutlineColor", name = "Self Outline Color", position = 4, section = selfSection, description = "Color and transparency of your outline")
	default Color selfOutlineColor() { return new Color(255, 255, 255, 255); }

	@ConfigSection(name = "Other Players", description = "Settings for nearby players", position = 10)
	String othersSection = "othersSection";

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "playerOpacity", name = "Ghost Opacity", position = 11, section = othersSection, description = "Transparency of nearby players")
	default int playerOpacity() { return 50; }

	@Range(min = 1, max = 50)
	@ConfigItem(keyName = "proximityRange", name = "Proximity Distance", position = 12, section = othersSection, description = "Radius for the effect")
	default int proximityRange() { return 10; }

	@ConfigItem(keyName = "ignoreFriends", name = "Ignore Friends", position = 13, section = othersSection, description = "Don't ghost your friends")
	default boolean ignoreFriends() { return false; }

	@ConfigItem(keyName = "limitAffectedPlayers", name = "Limit affected players", position = 14, section = othersSection, description = "Limit players for performance")
	default boolean limitAffectedPlayers() { return true; }

	@Range(min = 1, max = 100)
	@ConfigItem(keyName = "maxAffectedPlayers", name = "Max affected players", position = 15, section = othersSection, description = "Max players to ghost")
	default int maxAffectedPlayers() { return 8; }

	@ConfigItem(keyName = "othersOutline", name = "Outline Others", position = 16, section = othersSection, description = "Enable outline for ghosted players")
	default boolean othersOutline() { return false; }

	@Alpha
	@ConfigItem(keyName = "othersOutlineColor", name = "Others Outline Color", position = 17, section = othersSection, description = "Color/Alpha of others' outline")
	default Color othersOutlineColor() { return new Color(255, 255, 255, 150); }

	@ConfigItem(keyName = "hideStackedOutlines", name = "Hide Stacked Outlines", position = 18, section = othersSection, description = "Hides outlines for everyone on a stacked tile")
	default boolean hideStackedOutlines() { return true; }

	@ConfigSection(name = "Outline Style", description = "Adjust the thickness and blur of the outlines", position = 20)
	String outlineStyleSection = "outlineStyleSection";

	@Range(min = 1, max = 10)
	@ConfigItem(keyName = "outlineWidth", name = "Line Thickness", position = 21, section = outlineStyleSection, description = "Thickness of the main line")
	default int outlineWidth() { return 1; }

	@Range(min = 0, max = 10)
	@ConfigItem(keyName = "outlineFeather", name = "Line Blur (Feather)", position = 22, section = outlineStyleSection, description = "Softness of the main line")
	default int outlineFeather() { return 0; }

	@ConfigItem(keyName = "enableGlow", name = "Add Outer Glow", position = 23, section = outlineStyleSection, description = "Adds a secondary blurred layer")
	default boolean enableGlow() { return false; }

	@Range(min = 1, max = 20)
	@ConfigItem(keyName = "glowWidth", name = "Glow Thickness", position = 24, section = outlineStyleSection, description = "Width of the glow layer")
	default int glowWidth() { return 4; }

	@Range(min = 1, max = 10)
	@ConfigItem(keyName = "glowFeather", name = "Glow Blur", position = 25, section = outlineStyleSection, description = "Softness of the glow layer")
	default int glowFeather() { return 4; }
}