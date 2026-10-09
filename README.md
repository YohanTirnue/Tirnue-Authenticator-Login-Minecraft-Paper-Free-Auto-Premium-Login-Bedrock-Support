# TirnueAuth

TirnueAuth authenticates players on Paper 1.21.4+ servers before they enter the world. It provides true hybrid authentication on offline-mode (`online-mode=false`) servers: handling passwords, PacketEvents-powered cryptographic Mojang session verification, and Bedrock Floodgate auto-login in a single unified plugin.

* **Official Mojang Clients**: Undergo full cryptographic verification (RSA challenge, AES-CFB8 Netty ciphers, and Mojang `hasJoined` session query) for seamless auto-login with their real Mojang UUID.
* **Cracked Impostors**: If a cracked launcher attempts to connect using an official Mojang username, it fails the cryptographic challenge and is immediately kicked.
* **Cracked Players**: Genuine cracked players authenticate through Paper's native modal dialogs without loading chunks or ticking entities.

---

## Authentication Flow

```mermaid
flowchart TD
    A["Player Connects (LOGIN_START)"] --> B{"Client Type"}

    %% Bedrock Branch
    B -- "Bedrock / '.' Prefix" --> C{"Valid Floodgate / Xbox Live?"}
    C -- "Yes" --> D["Auto-Login (Bedrock)"]
    C -- "No (Dot Spoof)" --> K1["Kick: Dot Spoofing Denied"]

    %% Java Branch
    B -- "Java Client" --> E{"Account Registered in DB?"}

    %% Registered PREMIUM
    E -- "Registered as PREMIUM" --> F["Server sends RSA Encryption Request Challenge"]
    F --> G{"Client solves challenge & Mojang hasJoined?"}
    G -- "Yes (Official Mojang Client)" --> H["Install AES/CFB8 Netty Ciphers"]
    H --> I["Auto-Login with Mojang UUID"]
    G -- "No (Cracked Launcher / Impostor)" --> K2["Kick: Cracked Logins Forbidden"]

    %% Registered CRACKED
    E -- "Registered as CRACKED" --> J{"Valid IP Session?"}
    J -- "Yes" --> S1["Session Restored (Auto-Login)"]
    J -- "No" --> P1["Paper Login Dialog"]
    P1 --> V1{"Password Correct?"}
    V1 -- "Yes" --> M["Allow In & Spawn World"]
    V1 -- "No / Timeout" --> K3["Kick: Invalid Password"]

    %% Unregistered Account
    E -- "Unregistered Account" --> U{"Client sends official Mojang UUID?"}
    U -- "Yes (Official Client)" --> F2["Server sends RSA Encryption Challenge"]
    F2 --> G2{"Client solves challenge & Mojang hasJoined?"}
    G2 -- "Yes" --> H2["Install AES Ciphers"] --> I2["Auto-Login & Save as PREMIUM"] --> M
    G2 -- "No" --> K4["Kick: Session Verification Failed"]

    U -- "No (Cracked Launcher)" --> N{"Registration Surge Active?"}
    N -- "Yes" --> O["In-Dialog Queue"]
    N -- "No" --> P2["Paper Register Dialog"]
    P2 --> V2{"Valid Password & Non-Bot?"}
    V2 -- "Yes" --> Q["Save as CRACKED"] --> M
    V2 -- "No" --> K5["Kick: Invalid Registration"]

    D --> M
    I --> M
    S1 --> M
```

---

## Features

* **Pre-Join Dialogs**: Cracked players must log in or register via Paper's native dialog screen (`io.papermc.paper.dialog.Dialog`). They never spawn into the world without authenticating.
* **Mojang Auto-Login & Strict Lockout**: Official Mojang accounts bypass passwords automatically. Once registered as premium, any cracked attempt to log into that username is instantly kicked with zero password prompts.
* **Bedrock Support**: Bedrock players log in automatically through Floodgate and Xbox Live. Java clients attempting to spoof `.` usernames are kicked on pre-login.
* **Anti-Bot Protection**: Form submissions faster than 800ms are kicked as bots. Rapid registration bursts automatically route new accounts into an in-dialogue queue.
* **Mojang API Throttling**: A token-bucket limiter keeps outbound Mojang lookups under rate limits to prevent IP bans and connection stalls.
* **AuthMe Importer**: Migrates existing user accounts, salts, and SHA-256 hashes straight out of `plugins/AuthMe/authme.db`.

---

## Commands

### Player Commands (`tirnue.auth.user`)

| Command | Aliases | Description |
| :--- | :--- | :--- |
| `/login <password>` | `/l`, `/log` | Log into your account (redirects cracked users to the pre-join dialog). |
| `/register <password> [confirm]` | `/reg` | Register an account with a password. |
| `/changepassword <old> <new>` | `/changepw`, `/cpw` | Change your password or set a premium backup password. |
| `/logout` | `/unlog` | End current session. |
| `/premium` | `/prem`, `/tpremium` | Turn on Mojang auto-login for your username. |
| `/cracked` | `/unpremium`, `/tcracked` | Switch account back to password mode. |

### Admin Commands (`tirnue.auth.admin`)

| Command | Description |
| :--- | :--- |
| `/tauth status` | View rate limiter tokens, join surge state, and queue activity. |
| `/tauth ipbind [on\|off]` | Toggle premium IP-binding checks. |
| `/tauth surge [on\|off]` | Toggle connection surge detection. |
| `/tauth ratelimit [on\|off]` | Toggle Mojang API token-bucket rate limiter. |
| `/tauth regsurge [on\|off\|reset]` | Toggle or reset the registration queue. |
| `/tauth check <player>` | Inspect an account's auth mode, UUID, IP, and login state. |
| `/tauth set <player> <mode>` | Change auth mode (`PREMIUM`, `CRACKED`, `BEDROCK`). |
| `/tauth unregister <player>` | Remove an account from the database. |
| `/tauth forcelogin <player>` | Force-login an online player. |
| `/tauth import` | Import accounts from `plugins/AuthMe/authme.db`. |
| `/tauth reload` | Reload configuration and messages. |

---

## Placeholders

Requires PlaceholderAPI:

| Placeholder | Outputs | Description |
| :--- | :--- | :--- |
| `%tirnueauth_is_logged_in%` | `true` / `false` | Authentication state. |
| `%tirnueauth_type%` | `BEDROCK`, `PREMIUM`, `CRACKED`, `UNREGISTERED` | Account type. |
| `%tirnueauth_registered%` | `true` / `false` | Registration state. |
| `%tirnueauth_is_bedrock%` | `true` / `false` | Bedrock client check. |

---

## Building

Requires JDK 21+ and a Paper 1.21.4+ server jar in classpath:

```bash
./build.sh
```
