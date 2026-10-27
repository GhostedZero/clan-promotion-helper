package com.clanpromotionhelper;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Clan Promotion Helper",
	description = "Works out which clan members are due a promotion from their time in the clan and XP gained",
	tags = {"clan", "rank", "promotion", "wise old man", "xp"}
)
public class ClanPromotionHelperPlugin extends Plugin
{
	private static final String CACHE_KEY = "xpCache";
	private static final String RANK_HISTORY_KEY = "rankHistory";
	/** How often the roster is re-read from clan settings (about 1.8 seconds) */
	private static final int ROSTER_CHECK_TICKS = 3;
	/** How often to look for members whose XP is due a refresh, unless the roster just changed (about 30 seconds) */
	private static final int STALE_CHECK_TICKS = 50;
	/**
	 * Spacing between lookups: about 17 a minute without a key and 80 with one. Wise Old Man's limits are 20 and
	 * 100 a minute per internet address, so this leaves room for other plugins that use it from the same address.
	 */
	private static final long DELAY_WITHOUT_KEY_MS = 3_500;
	private static final long DELAY_WITH_KEY_MS = 750;
	private static final int MAX_RETRIES = 2;
	private static final long MANUAL_REFRESH_COOLDOWN_MS = 60_000;
	/** Save the cache after this many new results, not after every one */
	private static final int SAVE_EVERY_RESULTS = 25;

	@Value
	static class Member
	{
		String name;
		String rank;
		/** Where XP, boss kills and months are counted from: the join date, or the last rank change seen */
		LocalDate since;
		LocalDate joinDate;
		/** The in-game rank number, for vanity rank conditions like "Sergeant or higher" */
		int rankNumber;
		/** The game's icon for the member's clan rank; null if RuneLite hasn't loaded it */
		BufferedImage rankIcon;
	}

	@Value
	static class Candidate
	{
		String name;
		String currentRank;
		String nextRank;
	}

	@Inject
	private Client client;

	@Inject
	private ClanPromotionHelperConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChatIconManager chatIconManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PromotionPanel promotionPanel;

	@Inject
	private PromotionOverlay promotionOverlay;

	@Inject
	private RulesEditorPanel rulesEditor;

	@Inject
	private MemberListHighlightOverlay memberListHighlightOverlay;

	@Inject
	private WiseOldManClient wiseOldManClient;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Getter
	private volatile List<Member> members = Collections.emptyList();

	@Getter
	private volatile List<Candidate> candidates = Collections.emptyList();

	/** Next rank keyed by {@link XpCache#key}, for the member list highlight */
	@Getter
	private volatile Map<String, String> candidateTargets = Collections.emptyMap();

	/** Current rank title of each member in {@link #candidateTargets}, keyed the same way */
	@Getter
	private volatile Map<String, String> candidateCurrentRanks = Collections.emptyMap();

	@Getter
	private volatile int checkedCount;

	@Getter
	private volatile int failedCount;

	@Getter
	private volatile boolean fetching;

	/** Every member as shown in the sidebar */
	private volatile List<PromotionRow> rows = Collections.emptyList();

	/** One-line note about the last Wise Old Man group refresh; null when no group is set */
	@Getter
	private volatile String groupStatus;

	private volatile RankRules rankRules;
	private volatile VanityRules vanityRules;
	/** In-game rank number of each rank title, keyed by lower-case title */
	private volatile Map<String, Integer> rankNumbers = Collections.emptyMap();
	/** Rank titles lowest first, for the rules editor */
	@Getter
	private volatile List<String> rankTitles = Collections.emptyList();
	/** Titles members can be promoted through (below administrator), lowest first, for filling the ladder */
	@Getter
	private volatile List<String> ladderTitles = Collections.emptyList();
	/** Boss names Wise Old Man has returned this session, so the editor can flag misspelt ones */
	@Getter
	private final Set<String> knownBosses = ConcurrentHashMap.newKeySet();
	private volatile XpCache xpCache = new XpCache();
	private volatile RankHistory rankHistory = new RankHistory();
	private String loadedProfileKey;
	private int rosterHash;
	private int ticksUntilRosterCheck;
	private int ticksUntilStaleCheck;
	/** Entries fetched before this time count as stale, whatever the refresh interval says */
	private volatile long forceRefreshBefore;
	private long lastManualRefresh;

	private final Deque<Member> fetchQueue = new ArrayDeque<>();
	private NavigationButton navigationButton;
	private ScheduledFuture<?> scheduledFetch;
	/** Bumped whenever fetching stops, so late responses from an old run are ignored */
	private int fetchGeneration;
	private int unsavedResults;
	/** Members the group request updated in the current run; null when this run didn't use the group */
	private Integer groupUpdatedThisRun;
	/** Size of the current run's one-by-one lookups, and how many of them failed */
	private int individualTotal;
	private int individualFailed;

	@Provides
	ClanPromotionHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanPromotionHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		rankRules = RankRules.parse(config.rankRequirements());
		vanityRules = VanityRules.parse(config.vanityRanks());
		ticksUntilRosterCheck = 1;
		overlayManager.add(promotionOverlay);
		overlayManager.add(memberListHighlightOverlay);

		navigationButton = NavigationButton.builder()
			.tooltip("Clan Promotion Helper")
			.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
			.priority(7)
			.panel(promotionPanel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		promotionPanel.start();
		promotionPanel.setRefreshAction(() -> executor.submit(this::requestRefresh));
		rulesEditor.setSources(this::getRankTitles, this::getLadderTitles, this::getKnownBosses);
		recompute();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(promotionOverlay);
		overlayManager.remove(memberListHighlightOverlay);
		clientToolbar.removeNavigation(navigationButton);
		stopFetching();
		saveCache();
		clearState();
		// Last, so the update clearState sends can't restart the panel's refresh timer
		promotionPanel.stop();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			stopFetching();
			saveCache();
			clearState();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (--ticksUntilRosterCheck > 0)
		{
			return;
		}
		ticksUntilRosterCheck = ROSTER_CHECK_TICKS;

		if (!loadCacheForProfile())
		{
			return;
		}

		ClanSettings settings = client.getClanSettings();
		if (settings == null)
		{
			return;
		}

		List<Member> roster = readRoster(settings, LocalDate.now());
		boolean rosterChanged = roster.hashCode() != rosterHash;
		if (rosterChanged)
		{
			rosterHash = roster.hashCode();
			members = Collections.unmodifiableList(roster);

			List<String> names = new ArrayList<>();
			for (Member member : roster)
			{
				names.add(member.getName());
			}
			xpCache.retainOnly(names);
			rankHistory.retainOnly(names);
			recompute();
		}

		// Refreshes are hours apart, so there's no need to scan every member on every roster check
		ticksUntilStaleCheck -= ROSTER_CHECK_TICKS;
		if (rosterChanged || ticksUntilStaleCheck <= 0)
		{
			ticksUntilStaleCheck = STALE_CHECK_TICKS;
			queueStaleMembers();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ClanPromotionHelperConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "countFromRankChange":
				// The next roster read picks up the new start dates, which also queues the lookups they need
				ticksUntilRosterCheck = 1;
				break;
			case "rankRequirements":
				rankRules = RankRules.parse(config.rankRequirements());
				recompute();
				break;
			case "vanityRanks":
				vanityRules = VanityRules.parse(config.vanityRanks());
				recompute();
				break;
			case "enableWiseOldMan":
				if (!config.enableWiseOldMan())
				{
					stopFetching();
				}
				ticksUntilStaleCheck = 0;
				break;
			case "ignoredPlayers":
			case "ignoredTargetRanks":
				recompute();
				break;
			default:
				break;
		}
	}

	private List<Member> readRoster(ClanSettings settings, LocalDate today)
	{
		boolean fromRankChange = config.countFromRankChange();
		Map<String, Integer> numbers = new HashMap<>();
		List<String> titles = new ArrayList<>();
		List<String> ladder = new ArrayList<>();
		// Rank numbers run from guest (-1) to Jagex moderator (127); unused ones have no title
		for (int i = -1; i <= 127; i++)
		{
			ClanTitle title = settings.titleForRank(new ClanRank(i));
			if (title != null && title.getName() != null && numbers.putIfAbsent(title.getName().toLowerCase(Locale.ROOT), i) == null)
			{
				titles.add(title.getName());
				// Guests aren't members, and administrator and up are appointed rather than promoted into
				if (i >= 0 && i < ClanRank.ADMINISTRATOR.getRank())
				{
					ladder.add(title.getName());
				}
			}
		}
		rankNumbers = Collections.unmodifiableMap(numbers);
		rankTitles = Collections.unmodifiableList(titles);
		ladderTitles = Collections.unmodifiableList(ladder);

		List<Member> roster = new ArrayList<>();
		for (ClanMember clanMember : settings.getMembers())
		{
			if (clanMember == null || clanMember.getName() == null || clanMember.getJoinDate() == null)
			{
				continue;
			}

			ClanTitle title = settings.titleForRank(clanMember.getRank());
			String name = clanMember.getName().replace('\u00A0', ' ').trim();
			BufferedImage icon = title == null ? null : chatIconManager.getRankImage(title);
			// Recorded even with the setting off, so turning it on later has the changes seen so far
			if (clanMember.getRank() != null)
			{
				rankHistory.record(name, clanMember.getRank().getRank(), today);
			}
			LocalDate since = fromRankChange ? rankHistory.since(name, clanMember.getJoinDate()) : clanMember.getJoinDate();
			int rankNumber = clanMember.getRank() == null ? Integer.MIN_VALUE : clanMember.getRank().getRank();
			roster.add(new Member(name, title == null ? null : title.getName(), since, clanMember.getJoinDate(), rankNumber, icon));
		}
		return roster;
	}

	/**
	 * Loads the saved XP for the logged-in account, switching caches if the account changed.
	 * Returns false until RuneLite knows which account is logged in.
	 */
	private boolean loadCacheForProfile()
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null)
		{
			return false;
		}

		if (!profileKey.equals(loadedProfileKey))
		{
			stopFetching();
			saveCache();
			XpCache loaded = XpCache.fromJson(configManager.getConfiguration(ClanPromotionHelperConfig.GROUP, profileKey, CACHE_KEY), gson);
			RankHistory history = RankHistory.fromJson(configManager.getConfiguration(ClanPromotionHelperConfig.GROUP, profileKey, RANK_HISTORY_KEY), gson);
			// Under the lock, so a lookup that finishes during the switch can't land in the new account's cache
			synchronized (this)
			{
				xpCache = loaded;
				rankHistory = history;
				loadedProfileKey = profileKey;
			}
			rosterHash = 0;
		}
		return true;
	}

	/** Saves to the account the cache was loaded for, even if RuneLite has already moved on from it */
	private void saveCache()
	{
		String profileKey = loadedProfileKey;
		if (profileKey == null)
		{
			return;
		}
		configManager.setConfiguration(ClanPromotionHelperConfig.GROUP, profileKey, CACHE_KEY, xpCache.toJson(gson));
		configManager.setConfiguration(ClanPromotionHelperConfig.GROUP, profileKey, RANK_HISTORY_KEY, rankHistory.toJson(gson));
		synchronized (this)
		{
			unsavedResults = 0;
		}
	}

	private void clearState()
	{
		members = Collections.emptyList();
		candidates = Collections.emptyList();
		candidateTargets = Collections.emptyMap();
		candidateCurrentRanks = Collections.emptyMap();
		rows = Collections.emptyList();
		groupStatus = null;
		checkedCount = 0;
		failedCount = 0;
		xpCache = new XpCache();
		rankHistory = new RankHistory();
		loadedProfileKey = null;
		rosterHash = 0;
		promotionPanel.update(rows, summary());
	}

	private List<Member> staleMembers(long now)
	{
		int refreshHours = config.refreshHours();
		// With a group, a forced refresh can cover everyone cheaply. Without one it would mean one request
		// per member, so it only retries members who have no result yet or whose lookup failed.
		boolean everyone = config.womGroupId() > 0;
		List<Member> stale = new ArrayList<>();
		for (Member member : members)
		{
			XpCache.Entry entry = xpCache.get(member.getName());
			// Only a forced refresh fills in missing data; doing it on every check could loop on a response without it
			boolean forced = entry != null && entry.getFetchedAt() < forceRefreshBefore
				&& (everyone || entry.getXpGained() == null || missingData(member, entry));
			if (forced || xpCache.needsFetch(member.getName(), member.getSince(), now, refreshHours))
			{
				stale.add(member);
			}
		}
		return stale;
	}

	/** The sidebar's Refresh button: re-checks XP now. Ignored while a refresh is running or just after one. */
	private void requestRefresh()
	{
		synchronized (this)
		{
			long now = System.currentTimeMillis();
			if (fetching || !config.enableWiseOldMan())
			{
				return;
			}

			if (now - lastManualRefresh < MANUAL_REFRESH_COOLDOWN_MS)
			{
				groupStatus = "Please wait before refreshing again";
				recompute();
				return;
			}

			lastManualRefresh = now;
			forceRefreshBefore = now;
			groupStatus = config.womGroupId() > 0 ? null : "Retrying members with no result";
		}
		recompute();
		queueStaleMembers();
		recompute();
	}

	/**
	 * Starts a refresh when someone is due one. With a Wise Old Man group set, a single group request first
	 * brings everyone who has a saved baseline up to date; only the members it can't cover (no baseline yet,
	 * not in the group, unusable total) are then looked up one by one.
	 */
	private synchronized void queueStaleMembers()
	{
		if (fetching || !config.enableWiseOldMan())
		{
			return;
		}

		List<Member> stale = staleMembers(System.currentTimeMillis());
		if (stale.isEmpty())
		{
			return;
		}

		fetching = true;
		int groupId = config.womGroupId();
		if (groupId > 0 && hasBaseline(stale))
		{
			groupStatus = "Refreshing from group " + groupId + "\u2026";
			int generation = fetchGeneration;
			wiseOldManClient.fetchGroup(groupId, config.apiKey(), config.contact(),
				result -> onGroupResult(generation, result));
			return;
		}

		groupUpdatedThisRun = null;
		groupStatus = groupId > 0 ? "Saving starting XP for group refreshes" : null;
		startIndividualLookups(stale);
	}

	/** Whether the member needs more than total XP, which only a one-by-one lookup gives. */
	private boolean needsFullLookup(Member member)
	{
		return rankRules.nextNeedsBosses(member.getRank()) || vanityRules.appliesTo(member.getRank());
	}

	/** Whether the entry lacks boss kills or levels the member's rules need, e.g. because it came from a group refresh. */
	private boolean missingData(Member member, XpCache.Entry entry)
	{
		return (entry.getBossKills() == null && rankRules.nextNeedsBosses(member.getRank()))
			|| (entry.getLevels() == null && vanityRules.appliesTo(member.getRank()));
	}

	private boolean hasBaseline(List<Member> stale)
	{
		for (Member member : stale)
		{
			if (xpCache.baselineFor(member.getName(), member.getSince()) != null)
			{
				return true;
			}
		}
		return false;
	}

	private void onGroupResult(int generation, WiseOldManClient.GroupResult result)
	{
		synchronized (this)
		{
			if (generation != fetchGeneration)
			{
				return;
			}

			long now = System.currentTimeMillis();
			if (result.isOk())
			{
				int updated = 0;
				for (Member member : members)
				{
					// The group only has total XP; members who need boss kills or levels stay stale and get a full lookup
					if (!needsFullLookup(member) && xpCache.applyGroupXp(member.getName(), member.getSince(), result.getExp(), now))
					{
						updated++;
					}
				}

				int remaining = staleMembers(now).size();
				groupUpdatedThisRun = updated;
				groupStatus = "Group: " + updated + " updated" + (remaining > 0 ? ", looking up " + remaining + "\u2026" : "");
			}
			else
			{
				log.debug("Wise Old Man group refresh failed: {}", result.getError());
				groupUpdatedThisRun = null;
				groupStatus = "Group refresh failed: " + result.getError();
			}

			startIndividualLookups(staleMembers(now));
		}
		recompute();
	}

	/** Queues the given members for one-by-one lookups, or finishes the refresh if there are none. */
	private synchronized void startIndividualLookups(List<Member> toLookUp)
	{
		fetchQueue.clear();
		fetchQueue.addAll(toLookUp);
		individualTotal = toLookUp.size();
		individualFailed = 0;

		if (fetchQueue.isEmpty())
		{
			// Nothing left after the group request, so its line ("Group: N updated") is already final
			groupUpdatedThisRun = null;
			fetching = false;
			saveCache();
			return;
		}

		log.debug("Looking up XP for {} clan members", fetchQueue.size());
		fetching = true;
		scheduleNext(0, fetchGeneration);
	}

	private synchronized void stopFetching()
	{
		fetchQueue.clear();
		if (scheduledFetch != null)
		{
			scheduledFetch.cancel(false);
			scheduledFetch = null;
		}
		fetching = false;
		fetchGeneration++;
	}

	/**
	 * Schedules the next lookup for the run that {@code generation} belongs to. A run that has since been
	 * stopped must not schedule anything, or it would keep going alongside the next run and double the
	 * request rate.
	 */
	private synchronized void scheduleNext(long delayMs, int generation)
	{
		if (generation != fetchGeneration)
		{
			return;
		}
		scheduledFetch = executor.schedule(() -> fetchNext(generation), delayMs, TimeUnit.MILLISECONDS);
	}

	private void fetchNext(int generation)
	{
		Member member;
		synchronized (this)
		{
			if (generation != fetchGeneration)
			{
				return;
			}

			member = fetchQueue.poll();
			if (member == null)
			{
				fetching = false;
				// The group line was written before these lookups ran; say how they went now they're done
				if (groupUpdatedThisRun != null)
				{
					groupStatus = "Group: " + groupUpdatedThisRun + " updated, " + individualTotal + " looked up"
						+ (individualFailed > 0 ? " (" + individualFailed + " failed)" : "");
					groupUpdatedThisRun = null;
				}
			}
		}

		if (member == null)
		{
			saveCache();
			recompute();
			return;
		}

		lookUp(member, 0, generation);
	}

	private void lookUp(Member member, int attempt, int generation)
	{
		// Wise Old Man rejects a range that starts and ends on the same day, which would fail everyone who
		// joined today; ending tomorrow also counts XP gained so far today
		wiseOldManClient.fetchXpGained(member.getName(), member.getSince(), LocalDate.now().plusDays(1),
			config.apiKey(), config.contact(), result -> onResult(member, attempt, generation, result));
	}

	private void onResult(Member member, int attempt, int generation, WiseOldManClient.Result result)
	{
		synchronized (this)
		{
			if (generation != fetchGeneration)
			{
				return;
			}

			if (result.getStatus() == WiseOldManClient.Result.Status.RETRY && attempt < MAX_RETRIES)
			{
				log.debug("Retrying {} in {}s: {}", member.getName(), result.getRetryAfterSeconds(), result.getError());
				scheduledFetch = executor.schedule(() -> lookUp(member, attempt + 1, generation),
					result.getRetryAfterSeconds(), TimeUnit.SECONDS);
				return;
			}

			// Written while still holding the lock that checked the run, so stopping or switching accounts
			// can't happen between the check and the write
			long now = System.currentTimeMillis();
			String since = member.getSince().toString();
			// A baseline stays valid for this name and join date, so a failed lookup mustn't throw it away
			Long savedBaseline = xpCache.baselineFor(member.getName(), member.getSince());
			if (result.getStatus() == WiseOldManClient.Result.Status.OK)
			{
				Long baseline = result.getStartXp() != null ? result.getStartXp() : savedBaseline;
				knownBosses.addAll(result.getKills().keySet());
				boolean vanity = !vanityRules.getRules().isEmpty();
				xpCache.put(member.getName(), new XpCache.Entry(result.getXpGained(), now, since, null, baseline, result.getBossKills(),
					vanity ? result.getLevels() : null, vanity ? withKills(result.getKills()) : null));
			}
			else
			{
				log.debug("XP lookup for {} failed: {}", member.getName(), result.getError());
				xpCache.put(member.getName(), new XpCache.Entry(null, now, since, result.getError(), savedBaseline));
				individualFailed++;
			}
		}

		recompute();

		boolean save;
		synchronized (this)
		{
			save = ++unsavedResults >= SAVE_EVERY_RESULTS;
		}
		if (save)
		{
			saveCache();
		}

		String apiKey = config.apiKey();
		scheduleNext(apiKey == null || apiKey.trim().isEmpty() ? DELAY_WITHOUT_KEY_MS : DELAY_WITH_KEY_MS, generation);
	}

	/** Only bosses with kills, since most players have none at most bosses and the cache is saved in the config */
	private static Map<String, Long> withKills(Map<String, Long> kills)
	{
		Map<String, Long> result = new HashMap<>();
		for (Map.Entry<String, Long> boss : kills.entrySet())
		{
			if (boss.getValue() > 0)
			{
				result.put(boss.getKey(), boss.getValue());
			}
		}
		return result;
	}

	private void recompute()
	{
		RankRules rules = rankRules;
		VanityRules vanity = vanityRules;
		Map<String, Integer> numbers = rankNumbers;
		XpCache cache = xpCache;
		Set<String> ignoredPlayers = splitList(config.ignoredPlayers(), true);
		Set<String> ignoredTargets = splitList(config.ignoredTargetRanks(), false);
		LocalDate today = LocalDate.now();

		List<PromotionRow> allRows = new ArrayList<>();
		List<Candidate> found = new ArrayList<>();
		Map<String, String> targets = new HashMap<>();
		Map<String, String> currentRanks = new HashMap<>();
		int checked = 0;
		int failed = 0;

		for (Member member : members)
		{
			XpCache.Entry entry = cache.get(member.getName());
			Long xp = entry == null ? null : entry.getXpGained();
			Long bosses = entry == null ? null : entry.getBossKills();
			RankRules.Evaluation evaluation = rules.evaluate(member.getRank(), member.getSince(), xp, bosses, today);

			if (entry != null)
			{
				checked++;
				if (xp == null)
				{
					failed++;
				}
			}

			boolean vanityApplies = vanity.appliesTo(member.getRank());
			List<String> vanityMet = null;
			if (vanityApplies && xp != null && entry.getLevels() != null)
			{
				VanityRules.Stats stats = new VanityRules.Stats(member.getRankNumber(),
					rankHistory.peak(member.getName(), member.getRankNumber()),
					RankRules.monthsInClan(member.getJoinDate(), today), entry.getLevels(),
					entry.getKills() == null ? Collections.emptyMap() : entry.getKills());
				vanityMet = vanity.qualified(member.getRank(), stats, numbers);
			}

			Outcome outcome = decide(evaluation.getStatus(), evaluation.getNextRank(), entry != null, xp != null,
				entry == null ? null : entry.getError(), vanityApplies, vanityMet,
				ignoredPlayers.contains(XpCache.key(member.getName())), ignoredTargets);
			PromotionStatus status = outcome.getStatus();
			String readyText = String.join(" / ", outcome.getTargets());
			if (status == PromotionStatus.READY)
			{
				found.add(new Candidate(member.getName(), member.getRank(), readyText));
				targets.put(XpCache.key(member.getName()), readyText);
				currentRanks.put(XpCache.key(member.getName()), member.getRank());
			}

			boolean showLadder = outcome.isShowLadder();
			allRows.add(new PromotionRow(member.getName(), member.getRank(),
				status == PromotionStatus.READY ? readyText : evaluation.getNextRank(),
				evaluation.getMonths(), showLadder ? evaluation.getRequiredMonths() : 0, showLadder ? evaluation.getRequiredXpMillions() : 0, xp,
				showLadder ? evaluation.getRequiredBosses() : 0, bosses, status, outcome.getDetail(), member.getRankIcon(),
				entry == null ? 0 : entry.getFetchedAt()));
		}

		found.sort(Comparator.comparing(Candidate::getName, String.CASE_INSENSITIVE_ORDER));
		candidates = Collections.unmodifiableList(found);
		candidateTargets = Collections.unmodifiableMap(targets);
		candidateCurrentRanks = Collections.unmodifiableMap(currentRanks);
		rows = Collections.unmodifiableList(allRows);
		checkedCount = checked;
		failedCount = failed;
		promotionPanel.update(rows, summary());
	}

	/** What the sidebar shows for one member, from the ladder result, the vanity result and the ignore lists. */
	@Value
	static class Outcome
	{
		PromotionStatus status;
		/** The ranks the member is ready for, ignored ones left out; empty unless READY */
		List<String> targets;
		/** Whether the ladder's progress bars belong on the card: not when it shows a vanity rank instead */
		boolean showLadder;
		/** Why the member is waiting or failed, for the tooltip; null otherwise */
		String detail;
	}

	/**
	 * @param error the lookup's error when it failed
	 * @param vanityMet the vanity ranks met, or null when vanity ranks don't apply or levels haven't been looked up
	 */
	static Outcome decide(PromotionStatus ladderStatus, String ladderNext, boolean hasEntry, boolean hasXp, String error,
		boolean vanityApplies, List<String> vanityMet, boolean playerIgnored, Set<String> ignoredTargets)
	{
		PromotionStatus status = ladderStatus;
		String detail = null;
		// A rank that isn't on the ladder, or tops it, can still lead to a vanity rank
		boolean ladderOnly = (status == PromotionStatus.UNKNOWN_RANK || status == PromotionStatus.MAX_RANK) && !vanityApplies;
		if (!hasEntry)
		{
			status = ladderOnly ? status : PromotionStatus.PENDING;
		}
		else if (!hasXp)
		{
			detail = error;
			status = ladderOnly ? status : PromotionStatus.FAILED;
		}
		else if (status == PromotionStatus.NO_XP)
		{
			// XP came from a group refresh, which has no boss kills
			status = PromotionStatus.PENDING;
			detail = "Boss kills not looked up yet: they're checked at the next refresh, or use Refresh now";
		}

		List<String> ready = new ArrayList<>();
		if (status == PromotionStatus.READY)
		{
			ready.add(ladderNext);
		}
		if (vanityApplies && hasXp)
		{
			if (vanityMet != null)
			{
				ready.addAll(vanityMet);
			}
			else if (ready.isEmpty())
			{
				status = PromotionStatus.PENDING;
				detail = "Levels and kill counts not looked up yet: they're checked at the next refresh, or use Refresh now";
			}
		}

		boolean anyReady = !ready.isEmpty();
		ready.removeIf(next -> ignoredTargets.contains(next.toLowerCase(Locale.ROOT)));
		if (anyReady && (ready.isEmpty() || playerIgnored))
		{
			status = PromotionStatus.IGNORED;
			ready.clear();
		}
		else if (anyReady)
		{
			status = PromotionStatus.READY;
		}
		boolean showLadder = status != PromotionStatus.READY || ready.contains(ladderNext);
		return new Outcome(status, Collections.unmodifiableList(ready), showLadder, detail);
	}

	private PromotionPanel.Summary summary()
	{
		List<String> ruleErrors = new ArrayList<>();
		if (rankRules != null)
		{
			ruleErrors.addAll(rankRules.getErrors());
		}
		if (vanityRules != null)
		{
			ruleErrors.addAll(vanityRules.getErrors());
			ruleErrors.addAll(vanityRules.unknownRanks(rankNumbers.keySet()));
		}
		return new PromotionPanel.Summary(members.size(), candidates.size(), checkedCount, failedCount,
			config.enableWiseOldMan(), fetching, groupStatus, ruleErrors);
	}

	private static Set<String> splitList(String value, boolean names)
	{
		Set<String> result = new HashSet<>();
		if (value == null)
		{
			return result;
		}

		for (String part : value.split(","))
		{
			String trimmed = part.trim();
			if (!trimmed.isEmpty())
			{
				result.add(names ? XpCache.key(trimmed) : trimmed.toLowerCase(Locale.ROOT));
			}
		}
		return result;
	}
}
