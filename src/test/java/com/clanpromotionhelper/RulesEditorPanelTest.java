package com.clanpromotionhelper;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class RulesEditorPanelTest
{
	@Test
	public void clanLadderKeepsGameOrderAndSkipsVanityRanks()
	{
		assertEquals(Arrays.asList("Gnome child", "Recruit", "Sergeant"),
			RulesEditorPanel.clanLadder(Arrays.asList("Gnome child", "Recruit", "PvM Legend", "Sergeant"),
				Collections.singleton("pvm legend")));
	}
}
