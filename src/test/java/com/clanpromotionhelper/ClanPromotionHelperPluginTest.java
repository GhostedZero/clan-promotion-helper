package com.clanpromotionhelper;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ClanPromotionHelperPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ClanPromotionHelperPlugin.class);
		RuneLite.main(args);
	}
}
