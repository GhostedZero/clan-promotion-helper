# Clan Promotion Helper

A RuneLite plugin for clan leaders. It works out which clan members are due a promotion from how long they've been in the clan and how much XP they've gained since joining. It lists them in a sidebar and an on-screen panel, and highlights them in the Clan Settings member list.

<img src="screenshots/ready.png" width="225" alt="The Ready tab of the sidebar, with made-up members">

*The members shown are made up. In game, each name shows the member's clan rank icon.*

## How it works

1. The plugin reads your clan's members, ranks and join dates straight from the game.
2. If you turn on Wise Old Man lookups, it fetches each member's XP gained since they joined from [wiseoldman.net](https://wiseoldman.net), one member at a time in the background.
3. A member is due a promotion once they have both the months in the clan and the XP their **next** rank needs. A month counts once its join day arrives, so someone who joined on the 25th reaches 2 months on the 25th, not the 24th.

## Setup

In the plugin's settings:

1. **Look up XP on Wise Old Man:** turn this on. It's off by default because it sends requests to a third-party site.
2. **Contact name:** your Discord name. It's sent with each lookup so Wise Old Man can reach you about your usage.
3. **API key** (optional): raises Wise Old Man's limit from 20 to 100 lookups a minute. Ask for one on the [Wise Old Man Discord](https://wiseoldman.net/discord).
4. **Rank requirements:** check the table below matches your clan.
5. **Wise Old Man group ID** (optional): if your clan has a [group](https://wiseoldman.net/groups), enter its ID (the number at the end of its web address). After the first fill, a refresh then takes one request for the whole group instead of one per member. If the group request fails, the plugin falls back to looking members up one by one.

A clan of about 500 members takes around 28 minutes to check without an API key and about 6 minutes with one. Results are saved per account and only refreshed after the refresh interval (6 hours by default), so later logins are quick.

## Sidebar

Click the plugin's icon in RuneLite's sidebar to see every clan member:

- Tabs for **Ready**, **Later** (not ready yet), **Failed** and **All**, plus a name search.
- Each member shows their rank, months in the clan, XP gained and progress towards the next rank.
- **Refresh now** re-checks XP straight away. With a group ID set it refreshes everyone with one request; without one it only retries members with no result or a failed lookup.

## Rank requirements

One rank per line, lowest first, as `Rank:months:XP in millions`. This is an example ladder; replace it with your clan's own rank titles and requirements:

```
Thief:0:0
Recruit:1:0.1
Corporal:2:0.5
Sergeant:3:1
Lieutenant:4:2
Captain:5:3
General:6:8
Officer:9:12
Commander:12:20
Colonel:15:25
Brigadier:17:30
Admiral:20:50
Marshal:24:75
```

If a title doesn't match, those members show "Rank not in table" and are never suggested. Leave out ranks you don't promote to, such as Owner.

## Privacy

Wise Old Man lookups are **off by default**. When you turn them on, the plugin sends only these things, and only to [wiseoldman.net](https://wiseoldman.net):

- each clan member's **name** and **join date**, so Wise Old Man can say how much XP they gained since then;
- your **contact name** and **API key**, if you entered them.

Like any web request, this shows your IP address to Wise Old Man. Results are saved only on your own computer, and the plugin never changes anything on Wise Old Man: it only reads.

## Notes

- A failed lookup, for example for a player Wise Old Man doesn't track, is never treated as 0 XP. It shows under Failed and is retried after the refresh interval.
- XP gained only counts from when Wise Old Man first tracked the player, so members who weren't tracked before joining may show less than they really gained.

## Credits

The member-list highlighting follows the approach of the [Clan Rank Helper](https://github.com/homer2011/clan-rank-helper) plugin (BSD-2).
