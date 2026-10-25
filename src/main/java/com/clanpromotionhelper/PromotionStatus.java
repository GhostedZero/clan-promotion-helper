package com.clanpromotionhelper;

/**
 * Why a clan member is or isn't due a promotion. Declared in the order the sidebar lists them.
 */
enum PromotionStatus
{
	READY("Ready"),
	NOT_READY("Not ready"),
	PENDING("Waiting for XP"),
	FAILED("Lookup failed"),
	IGNORED("Ignored"),
	UNKNOWN_RANK("Rank not in table"),
	MAX_RANK("Top rank"),
	/** Only produced by {@link RankRules#evaluate}; the plugin reports it as PENDING or FAILED. */
	NO_XP("No XP data");

	private final String label;

	PromotionStatus(String label)
	{
		this.label = label;
	}

	String getLabel()
	{
		return label;
	}
}
