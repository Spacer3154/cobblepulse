# CobblePulse

Mod **Fabric 1.21.1** pour **Cobblemon 1.8.4** qui structure la compétition et la vie communautaire du serveur CobblePulse :

1. **Clans & Guerres d'Influence** (sans claims) : recrutement modulable, inventaire partagé 27/54 emplacements, statistiques PvP inter-clans, influence, guerres de 7 jours et classement.
2. **Ligue PvP** : 8 Champions (1 type), 4 Conseil 4 (2 types), 1 Maître, arènes avec points de TP, ouverture/fermeture, file d'attente (5), combats de Badge et de Place, cooldowns, anti-inactivité, anti-déconnexion, étui à badges verrouillé et audit staff.
3. **Tournois** : 10 formats (AG, Uber, OU, UU, RU, PU, ZU, Monotype, NFE, LC) avec banlists, randomizer avec byes, arènes multiples, annonces par échelon, grade temporaire du vainqueur et récompenses hybrides (items + commandes).
4. **Carte de Dresseur** (`/profile`) et **menu principal** (`/cobblepulse`, `/cp`).

---

## Installation

| Côté | Mods requis |
|---|---|
| Serveur | Fabric Loader ≥ 0.16, Fabric API, Fabric Language Kotlin, **Cobblemon 1.8.4**, **CobblePulse** |
| Serveur (recommandé) | **LuckPerms** (permissions et grades automatiques), **Text Placeholder API** (tag de clan dans ShadowChat), **ShadowChat** |
| Client | Rien de plus que le pack Cobblemon habituel. Les menus sont des coffres pilotés par le serveur ; le `.jar` peut aussi être inclus dans le modpack client sans effet de bord. |

1. Déposer `cobblepulse-1.0.0.jar` dans `mods/`.
2. Démarrer une première fois : les fichiers sont créés dans `config/cobblepulse/`.
3. Configurer les arènes (voir *Mise en route*) puis `/cobblepulse reload` si vous modifiez les fichiers.

Les bibliothèques HikariCP, MySQL Connector/J et NightConfig (TOML) sont embarquées dans le jar (Jar-in-Jar).

---

## Fichiers de configuration (`config/cobblepulse/`)

| Fichier | Contenu |
|---|---|
| `storage.toml` | `mode = "json"` (défaut) ou `"mysql"` + identifiants MySQL et taille du pool HikariCP |
| `league.toml` | cooldowns (24 h badge, 7 j place, 7 j global), file (5), délai d'acceptation, inactivité (jours, refus max), grâce de déconnexion (60 s), préfixes LuckPerms, effets VIP |
| `league_items.toml` | items des 13 badges au format `modid:item_name` (items d'autres mods) |
| `clans.toml` | longueurs nom/tag, membres max, inventaire 27/54, format du tag de chat, influence quotidienne par poste |
| `clan_wars.toml` | durée (7 j), % d'influence volée, points par victoire, anti-farm, récompenses (commandes) |
| `tournament.toml` | petite finale, rondes auto/manuelles, compte à rebours, grade du vainqueur et moment de son retrait |
| `tournament_rewards.toml` | 9 paliers (1er … Top 101-500), plages de places et commandes console (`%player%`) |
| `tournament_tiers/<tier>.toml` | `banned_pokemon`, `banned_items`, `banned_abilities`, `banned_moves`, `level_cap`, `inherits`, `[rules]` (species clause, monotype, LC/NFE…). Ajouter un fichier crée un nouveau format. |
| `messages.toml` | tous les messages et annonces (codes `&` et `&#RRGGBB`) |

### Stockage

* **JSON** : `data/clans/[clan_id].json`, `data/league/holders.json`, `data/badge_cases/[uuid].json`, `data/state/*.json` (guerres, tournoi, coffres de récompenses). Écritures atomiques (`.tmp` puis renommage).
* **MySQL** : tables `cp_clans`, `cp_clan_members`, `cp_league_holders`, `cp_player_badges`, plus `cp_players` (cooldowns, stats, audit) et `cp_kv` (état annexe). Créées automatiquement.
* Toutes les lectures/écritures passent par un thread d'E/S dédié. Les données fréquentes (titulaires, clans, dossiers des joueurs connectés) sont en cache RAM et écrites par lots toutes les 3 secondes, puis intégralement à l'arrêt.

---

## Mise en route rapide

```text
# Ligue : assigner un titulaire et ses types (ou /league admin en GUI)
/league admin set champion_1 Pierre
/league admin types champion_1 fire
/league admin types elite_1 dragon flying
# Le titulaire (ou un admin) place ses points de TP dans son arène puis ouvre l'arène
/league arena settp holder
/league arena settp challenger
/league arena toggle

# Tournoi : au moins une arène (plusieurs = matchs simultanés)
/tournoi arena add A1
/tournoi arena set A1 1      # position du joueur 1
/tournoi arena set A1 2      # position du joueur 2
/tournoi create ou Tournoi du samedi
/tournoi start               # randomizer : clôture les inscriptions et génère l'arbre
/tournoi nextround           # lance chaque ronde (ou auto_start_rounds = true)
```

Pour ShadowChat, ajoutez `%cobblepulse:clan_tag%` dans votre format de chat (`config/shadowchat/chat_config.json`). Le préfixe de Ligue et `[Vainqueur Tournoi]` sont des préfixes LuckPerms, déjà affichés par `%luckperms:prefix%`.

---

## Commandes

### Joueurs
| Commande | Effet |
|---|---|
| `/cobblepulse` · `/cp` | Menu principal (Étui, Clan, Ligue, Tournois, Profil) |
| `/league` | Vue des 13 postes, statut OUVERTE/FERMÉE, file, lancement des défis (clic gauche Badge, clic droit Place) |
| `/league badges` | Étui à badges verrouillé |
| `/league challenge <poste> <badge\|place>` · `/league queue leave` | Défi / quitter la file |
| `/league accept` · `/league refuse` · `/league arena [settp holder\|challenger\|toggle]` · `/league resign` | Titulaires |
| `/clan` | Gestion du clan (membres, inventaire, recrutement, guerres, classement) |
| `/clan create <nom> <tag>` · `join` · `request` · `accept` · `deny` · `leave` · `invite` · `kick` · `promote` · `demote` · `transfer` · `disband` · `mode <open\|invite\|closed>` · `inventory` · `tagcolor <&a\|&#RRGGBB>` · `list` · `top` · `info` · `requests` · `war declare <clan>` · `war status` | |
| `/tournoi` (`/tournament`) · `join` · `leave` · `bracket` · `claim` · `status` | Tournois |
| `/profile [joueur]` | Carte de Dresseur |

### Staff
| Commande | Permission |
|---|---|
| `/league inspect <joueur>` (hors-ligne OK, survol = audit, clic droit = retrait) | `cobblepulse.staff.inspect` / `.staff.badges.remove` |
| `/league cooldown reset <joueur> [all\|place\|<poste>]` | `cobblepulse.staff.cooldown.reset` |
| `/league admin` + `list`, `set`, `vacate`, `types`, `settp`, `open`, `close`, `resetcooldown` | `cobblepulse.admin.league` |
| `/tournoi create <tier> [nom]` | `cobblepulse.tournament.create` |
| `/tournoi start` · `nextround` · `cancel` | `cobblepulse.tournament.manage` |
| `/tournoi disqualify <joueur> [raison]` (ou en un clic dans le GUI) | `cobblepulse.tournament.disqualify` |
| `/tournoi arena add\|set\|remove\|list` | `cobblepulse.tournament.arena.set` |
| `/tournoi rewards edit <palier>` | `cobblepulse.tournament.rewards` |
| `/cobblepulse reload` · `season reset <badges\|league\|clans\|all> confirm` · `selftest` | `cobblepulse.admin.*` (ou `cobblepulse.*`) |

---

## Permissions LuckPerms

Sans LuckPerms (ou si un nœud n'est pas défini), les nœuds joueurs sont accordés par défaut et les nœuds staff sont réservés aux opérateurs.

* **Joueurs** : `cobblepulse.menu.open`, `cobblepulse.league.view`, `cobblepulse.league.badges`, `cobblepulse.league.challenge.badge`, `cobblepulse.league.challenge.place`, `cobblepulse.clan.create`, `cobblepulse.clan.use`, `cobblepulse.clan.inventory`, `cobblepulse.tournament.view`, `cobblepulse.tournament.join`, `cobblepulse.tournament.claim`, `cobblepulse.profile.view`
* **VIP** : `cobblepulse.clan.inventory.expand` (54 emplacements), `cobblepulse.clan.chat.color`, `cobblepulse.cosmetic.arena_effect`
* **Rôles dynamiques (gérés par le mod)** : `cobblepulse.league.role.champion|elite|master`, `cobblepulse.league.arena.settp`, `cobblepulse.league.arena.toggle`, `cobblepulse.tournament.winner` + préfixes `[Champion - Type]`, `[Conseil 4 - Type 1 / Type 2]`, `[Maître]`, `[Vainqueur Tournoi]`
* **Animateurs** : `cobblepulse.tournament.create`, `.manage`, `.disqualify`, `.arena.set`, `.rewards`
* **Modérateurs** : `cobblepulse.staff.inspect`, `cobblepulse.staff.badges.remove`, `cobblepulse.staff.cooldown.reset`, `cobblepulse.tournament.*`
* **Administrateurs** : `cobblepulse.*`

Exemple :
```text
/lp group default permission set cobblepulse.menu.open true
/lp group vip permission set cobblepulse.clan.inventory.expand true
/lp group animateur permission set cobblepulse.tournament.create true
/lp group moderateur permission set cobblepulse.staff.inspect true
/lp group admin permission set cobblepulse.* true
```

---

## Règles implémentées (résumé)

**Ligue**
* Combat de Badge : Champion sans prérequis ; Conseil 4 = 8 badges de Champions. Victoire = badge ajouté automatiquement avec métadonnées (date, titulaire, durée, tours, équipes). Défaite = 24 h avant de réaffronter ce titulaire.
* Combat de Place : Champion sans prérequis ; Conseil 4 et Maître = 8 + 4 badges. Le Maître ne se défie qu'en Place. Victoire = transfert immédiat du poste, des permissions et du grade ; si l'ancien titulaire était Maître, son étui est réinitialisé. Défaite = 7 jours de cooldown personnel. Tout changement de titulaire bloque les combats de Place sur ce poste pendant 7 jours.
* File : si le titulaire est occupé et l'arène ouverte, jusqu'à 5 challengers attendent ; à la fin d'un match, le suivant est téléporté automatiquement. Le premier défi doit être accepté par le titulaire (clic [ACCEPTER]/[REFUSER] dans le chat) ; un refus ou une absence de réponse compte comme un refus, et trop de refus consécutifs libèrent le poste.
* Inactivité : poste libéré après N jours sans connexion (annonce globale).
* Déconnexion pendant un match officiel (Ligue ou Tournoi) : 60 s de grâce ; au retour, le match est relancé depuis le début (Cobblemon ne conserve pas un combat interrompu), sinon forfait automatique.
* Étui à badges : menu verrouillé côté serveur (impossible de déplacer, retirer ou jeter). Les items affichés sont ceux configurés dans `league_items.toml`.

**Clans** : modes OPEN / INVITE_ONLY / CLOSED, inventaire partagé synchronisé entre membres (27 emplacements, 54 pour les VIP), victoires/défaites PvP entre clans différents, influence quotidienne des titulaires de Ligue, guerres de 7 jours (points de guerre en PvP, plafond anti-farm, transfert d'un % d'influence et récompenses configurables), podium du classement.

**Tournois** : vérification de l'équipe à l'inscription et avant chaque match, arbre aléatoire avec byes (puissance de 2 supérieure), matchs simultanés sur plusieurs arènes, disqualification en un clic (l'adversaire est qualifié), annonce à chaque fin de match avec l'échelon (32e … Finale), grade temporaire `[Vainqueur Tournoi]` retiré au tournoi suivant, récompenses par palier (items à récupérer avec `/tournoi claim` + commandes console).

---

## Compilation

```bash
./gradlew build      # Java 21 ; le jar est dans build/libs/
```

La CI GitHub Actions (`.github/workflows/build.yml`) compile le mod puis démarre un vrai serveur Fabric 1.21.1 avec Cobblemon 1.8.4, LuckPerms et Placeholder API, et exécute `/cobblepulse selftest` (dont un combat de Ligue réel entre deux joueurs factices). Les résultats sont publiés sur la branche `ci-output`.
