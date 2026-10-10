# PHASE 06.6 — COMPLETE SOCIAL / CHAT / STORIES / SUPPORT / NOTIFICATIONS / P2P / DEVICE-MEDIA FORENSIC AUDIT REPORT

**Project:** CineStream Users App (`CineStream Pro`)  
**Target:** `CineStream-Pro00-main.zip`  
**Mode:** STRICT READ-ONLY / ZERO PRODUCTION MODIFICATIONS  
**Audit Type:** Forensic Feature, Lifecycle, Data, Performance, Privacy, and Security Contract Audit  
**Date:** October 6, 2026  
**Artifact:** `/PHASES/PHASE_06.6_SOCIAL_CHAT_STORIES_SUPPORT_NOTIFICATIONS_P2P_FORENSIC_AUDIT_REPORT.md`  

---

## 0. Executive Summary & Verification of Invariants

This forensic audit evaluates every social, communication, support, notification, device-to-device sharing, audio, camera, and device-media component in the production CineStream Pro application. 

### Key High-Level Findings:
1. **P2P Path Traversal & Unclosed ServerSocket (CRITICAL):**
   - `MediaStorageUtils.getDestinationFile(context, id, extension)` directly combines the remote peer's provided `id` into `File(dir, "$id.$extension")` without sanitizing against `../` path traversal sequences (`MediaStorageUtils.kt:98-103`). An unauthenticated remote device on the local network can overwrite arbitrary private app files.
   - `P2PManager` binds a continuous `ServerSocket` and UDP responder on port 8888 (`P2PManager.kt:529`). When leaving `ShareScreen`, `onDispose` only calls `stopDiscovery()`, leaving the `ServerSocket`, multicast lock, and thread pools active indefinitely, draining battery and listening on the network (`ShareScreen.kt:255-258`).
   - If a peer attempts to connect while the app user is not on `ShareScreen`, `decisionDeferred.await()` hangs indefinitely (`P2PManager.kt:592-598`), deadlocking the socket handler thread.
2. **Social Chat Coroutine Leak & Inefficient List Allocations (HIGH):**
   - In `SocialViewModel.kt:67-76`, `startListening()` assigns `conversationJob` to a dummy delay job while launching `repo.getConversations().collect` unassigned in `viewModelScope`. Repeated calls to `startListening()` or `refreshUser()` leak duplicate Firestore snapshot listeners.
   - In `ChatScreen.kt:327`, `LazyColumn` renders `items(messages.reversed())` without specifying a `key`. Every single frame/emission reallocates a reversed list (`O(N)` allocation), and lack of keys causes entire list recompositions and item jumpiness.
   - No message idempotency: `SocialRepository.sendMessage()` generates a random Firestore doc ID per tap (`SocialRepository.kt:261`). Double-tapping or retries create duplicate messages in Firestore.
3. **Simulated Social Profile Activity & Local-Only Friends/Blocks (HIGH):**
   - In `PublicProfileScreen.kt:117-148`, the public user's watch history and favorite media lists are **completely simulated**: it queries TMDB trending movies and shuffles them with `Random(userId.hashCode())`.
   - `blockedUsers` and `friendRequests` are stored **only locally** in device DataStore (`UserPreferencesRepository.kt:37-60`). There are no cloud Firestore collections (`/users/{uid}/blocked` or `/users/{uid}/friends`), nor do Firestore security rules enforce blocks. A blocked user can continue sending messages.
4. **Stories Subsystem UI Dead-End & Unbounded Query (MEDIUM):**
   - In `SocialScreen.kt:188`, fetched stories are explicitly suppressed: `// For now, no actual stories are rendered until fetched, we just show add story`. Tapping "Add Story" displays a toast stating `story_feature_coming_soon` (`SocialScreen.kt:172`).
   - `SocialRepository.getStories()` executes an unbounded query on `db.collection("stories").orderBy("timestamp", DESCENDING)` with **no 24-hour limit** and **no document limit** (`SocialRepository.kt:108-117`).
5. **Cross-User Data Leakage in Support Tickets (MEDIUM):**
   - `SupportViewModel` caches support messages in local Room table `support_messages` (`SupportMessage.kt:6-12`). This table lacks a `userId` partition column. When User A logs out and User B logs in (or a guest opens Help & Support), User A's private support messages are displayed (`SupportViewModel.kt:30-32`).
   - Remote message deletions in Firestore are never pruned from Room: `SupportViewModel` only performs `dao.insertMessage()`.
6. **Thread-Blocking Calls & Camera Executor Leak (MEDIUM):**
   - `NotificationRepository.listenForAnnouncements()` executes `runBlocking(Dispatchers.IO)` inside a Firestore snapshot listener callback thread (`NotificationRepository.kt:156-158`).
   - `AppFirebaseMessagingService` executes `runBlocking(Dispatchers.IO)` in `resolveNotificationPreferences()` and `persistToRoom()` (`AppFirebaseMessagingService.kt:180, 207`).
   - `QrCodeScannerDialog.kt:86` instantiates `Executors.newSingleThreadExecutor()` without a `DisposableEffect` shutdown hook, leaking an active OS thread every time the scanner dialog is opened and closed.

---

## 1. Complete Feature Inventory

Below is the file-by-file, component-by-component inventory of all 25 audited subsystems:

| Feature / Domain | UI Layer | ViewModel Layer | Repository Layer | DAO / DB Entities | Firestore Paths | Room Tables / Storage | Network & Hardware APIs | Lifecycle & Scopes | Required Permissions |
|---|---|---|---|---|---|---|---|---|---|
| **Chat** | `ChatScreen.kt` | `ChatViewModel.kt` | `SocialRepository.kt` | N/A (In-memory `StateFlow`) | `/conversations/{convId}`, `/conversations/{convId}/messages/{msgId}` | In-memory `_messages` | Cloudinary REST upload, Firestore WebSocket | `viewModelScope`, Firestore `callbackFlow` | `INTERNET` |
| **Conversations** | `SocialScreen.kt` | `SocialViewModel.kt` | `SocialRepository.kt` | N/A (In-memory `StateFlow`) | `/conversations` (query: `participants` array-contains) | In-memory `_conversations` | Firestore WebSocket | `viewModelScope`, Firestore `callbackFlow` | `INTERNET` |
| **Stories** | `SocialScreen.kt` (Add Story stub) | `SocialViewModel.kt` | `SocialRepository.kt` | N/A (In-memory `StateFlow`) | `/stories/{storyId}` | In-memory `_stories` | Cloudinary REST upload, Firestore WebSocket | `viewModelScope`, Firestore `callbackFlow` | `INTERNET` |
| **User Search** | `SocialScreen.kt` | `SocialViewModel.kt` | `SocialRepository.kt` | N/A (In-memory `StateFlow`) | `/users` (query: `username >= q && <= q+\uf8ff`) | Memory `userProfileCache` | Firestore WebSocket | `viewModelScope` with debounced `Job` | `INTERNET` |
| **Public Profiles** | `PublicProfileScreen.kt` | State in Composable | `SocialRepository.kt`, `MediaRepository.kt` | N/A | `/users/{uid}` | Memory `userProfileCache` | TMDB API (mock activity generator) | Composable `LaunchedEffect` | `INTERNET` |
| **Own Profile** | `ProfileScreen.kt`, `EditProfileScreen.kt` | `AuthRepository.currentUserFlow` | `SocialRepository.kt`, `AuthRepository.kt` | N/A | `/users/{uid}` | DataStore `user_prefs` (custom avatar, bio) | Cloudinary upload, Firestore update | `viewModelScope` | `INTERNET` |
| **Friends Graph** | `PublicProfileScreen.kt` | N/A | `SocialRepository.kt` | N/A | Derived from `/conversations` | Local DataStore only | Firestore query | Composable lifecycle | `INTERNET` |
| **Friend Requests** | `PublicProfileScreen.kt` | N/A | `UserPreferencesRepository.kt` | N/A | None (Local only!) | DataStore `friend_requests` set | None | Local DataStore Flow | None |
| **User Blocking** | `BlockedUsersScreen.kt`, `PublicProfileScreen.kt` | N/A | `UserPreferencesRepository.kt`, `SocialRepository.kt` | N/A | None (Local only!) | DataStore `blocked_users` set | None | Local DataStore Flow | None |
| **Content & User Reporting** | `PublicProfileScreen.kt` (Dialog) | N/A | `ReportRepository.kt` | Model: `Report.kt` | `/reports/{reportId}` | None | Firestore task await | Composable `coroutineScope` | `INTERNET` |
| **Support Tickets** | `HelpSupportScreen.kt` | `SupportViewModel.kt` | Direct DAO + Firestore | `SupportDao.kt`, `SupportMessage.kt` | `/support_conversations/{uid}`, `/support_conversations/{uid}/messages/{msgId}` | Room table: `support_messages` | Firestore WebSocket & task await | `AndroidViewModel.viewModelScope`, `onCleared` | `INTERNET` |
| **Notifications Center** | `NotificationsScreen.kt` | `NotificationsViewModel.kt` | `NotificationRepository.kt` | `NotificationDao.kt`, `NotificationItem.kt` | `/notifications` (announcements query) | Room table: `notifications` | Firestore WebSocket, Local Room Flow | `viewModelScope`, Room Flow | `POST_NOTIFICATIONS` |
| **Notification Preferences** | `NotificationPreferencesScreen.kt`, `NotificationPreferencesDialog.kt` | `NotificationPreferencesViewModel.kt` | `NotificationPreferencesRepository.kt` | Model: `NotificationPreferences.kt` | None (Local only) | DataStore `notification_prefs` | None | `viewModelScope`, DataStore Flow | None |
| **FCM Push Messaging** | Background OS Tray | N/A | `NotificationRepository.kt`, `NotificationHelper.kt` | `NotificationDao.kt`, `NotificationItem.kt` | `/users/{uid}/fcmTokens/{installationId}` | Room table: `notifications`, DataStore `user_prefs` | FCM Gateway HTTP/2 | `FirebaseMessagingService` background binder | `POST_NOTIFICATIONS`, `INTERNET` |
| **FCM Token Management** | Background | N/A | `FcmTokenManager.kt` | Model: `FcmTokenDocument.kt` | `/users/{uid}/fcmTokens/{installationId}` | DataStore: `fcm_installation_id`, `fcm_cached_token` | FirebaseMessaging SDK, Firestore await | Standalone `SupervisorJob` IO Scope | `INTERNET` |
| **Audio Recording** | `ChatScreen.kt` | `ChatViewModel.kt` | N/A | `AudioRecorder.kt` | N/A | File: `cacheDir/audio_*.m4a` | Hardware Microphone (`MediaRecorder`) | Pointer input press/release scope | `RECORD_AUDIO` |
| **Audio Playback** | `ChatScreen.kt` | N/A | N/A | `AudioPlayer.kt` | N/A | None (Streaming audio URL) | `MediaPlayer` | DisposableEffect `AudioPlayer.stop()` | `INTERNET` |
| **Camera & QR Scanner** | `QrCodeScannerDialog.kt` | N/A | N/A | CameraX `ProcessCameraProvider` | N/A | None | CameraX Preview, ImageAnalysis, ZXing Decoder | Dialog Composable lifecycle | `CAMERA` |
| **QR Code Generation** | `ShareScreen.kt` | N/A | N/A | `QRCodeGenerator.kt` | N/A | Bitmap in memory | ZXing `QRCodeWriter` | Synchronous helper | None |
| **P2P Discovery** | `ShareScreen.kt` | N/A | `NearbyDeviceRepository.kt` | `P2PManager.kt` | None | `p2p_remembered_devices.xml` (SharedPreferences) | Google Nearby Connections, UDP Multicast (8888), Subnet TCP probe | `P2PManager.scope` (IO + SupervisorJob) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`, `CHANGE_WIFI_MULTICAST_STATE` |
| **P2P Socket Server** | `ShareScreen.kt` | N/A | `P2PManager.kt` | `P2PManager.kt` | None | None | Java `ServerSocket(8888)`, raw TCP streaming | Long-running background coroutines | `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` |
| **P2P File Transfer** | `ShareScreen.kt` | N/A | `P2PTransferRepository.kt`, `DownloadRepository.kt` | `P2PTransferRecord.kt`, `DownloadDao.kt` | None | `p2p_transfer_history.xml`, Room `downloads`, FilesDir `movies/` | Raw TCP socket `FileOutputStream`, Nearby Payload | Active transfer loop with notification updates | `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` |
| **P2P Transfer History** | `RecentTransfersScreen.kt` | N/A | `P2PTransferRepository.kt` | Model: `P2PTransferRecord.kt` | None | `p2p_transfer_history.xml` (JSON array in SharedPrefs) | None | Repository StateFlow | None |
| **Remembered Devices** | `ShareScreen.kt` | N/A | `NearbyDeviceRepository.kt` | Model: `NearbyDevice.kt` | None | `p2p_remembered_devices.xml` (JSON array in SharedPrefs) | None | Repository StateFlow | None |
| **Account Security & Bans** | Global | `UserSecurityManager.kt` | `UserSecurityManager.kt` | Model: `UserRestrictions.kt` | `/users/{uid}`, `/admins/{uid}` | In-memory `_restrictionsFlow` | Firestore snapshot listener | Application singleton lifecycle | `INTERNET` |

---

## 2. Social Architecture Graph

### 2.1 Complete Flow Diagrams

```
[Chat Flow]
ChatScreen (UI)
   │ (user types text / selects media)
   ▼
ChatViewModel.sendMessage()
   │
   ├── (if media != null) ──► SocialRepository.uploadMedia() ──► Cloudinary REST API ──► secure_url
   │                                                                                         │
   ▼                                                                                         ▼
SocialRepository.sendMessage() / sendMediaMessage()
   │
   ├──► Firestore Document Creation: /conversations/{convId}/messages/{randomDocId}
   └──► Firestore Transaction: update /conversations/{convId} (lastMessage, lastMessageTime, unreadCounts)
                                      │
                                      ▼
             Firestore Snapshot Listener: /conversations/{convId}/messages (ASC)
                                      │
                                      ▼ (PrivateMessage list)
                            ChatViewModel._messages (StateFlow)
                                      │
                                      ▼
                            ChatScreen: LazyColumn (items(messages.reversed()))
```

```
[Support Conversation Flow]
HelpSupportScreen (UI)
   │ (user submits message)
   ▼
SupportViewModel.sendMessage()
   │
   ├── 1. Immediate Local Cache: AppDatabase.supportDao().insertMessage() ──► Room table: support_messages
   │                                                                                      │
   └── 2. Cloud Synchronization (if authenticated):                                       ▼
          Firestore Set: /support_conversations/{user.uid}                   SupportViewModel.messages (StateFlow)
          Firestore Set: /support_conversations/{user.uid}/messages/{msgId}               │
                     │                                                                    ▼
                     ▼                                                           HelpSupportScreen (UI)
          Firestore Snapshot Listener: /support_conversations/{user.uid}/messages
                     │
                     ▼
          AppDatabase.supportDao().insertMessage() (sync back to Room)
```

```
[Stories Flow]
SocialScreen (UI)
   │
   ├── Add Story Button: onClick ──► Toast("Story feature coming soon") [DEAD END]
   │
   └── (Underlying Architecture - Not Connected to UI):
       SocialViewModel.stories (StateFlow)
          ▲
          │ collect
       SocialRepository.getStories()
          ▲
          │ Firestore callbackFlow
       Firestore: /stories (ORDER BY timestamp DESC) [UNBOUNDED QUERY]
```

```
[Public Profile & Social Graph Flow]
PublicProfileScreen (UI)
   │
   ├── 1. User Profile: SocialRepository.getUserProfile(uid) ──► Firestore: /users/{uid}
   │
   ├── 2. Friend Relationship: Check if currentUser.uid & targetUid in active conversation participants
   │
   ├── 3. Friend Request: UserPreferencesRepository.sendFriendRequest(uid) ──► Local DataStore [LOCAL ONLY]
   │
   ├── 4. User Blocking: UserPreferencesRepository.blockUser(uid) ──► Local DataStore [LOCAL ONLY]
   │
   └── 5. Watch Activity / Favorites:
          AppContainer.mediaRepository.getTrendingMovies/Series()
             │
             ▼
          Deterministic Pseudo-Random Shuffle: Random(userId.hashCode()) [SIMULATED DATA]
```

---

## 3. Chat Forensics

### 3.1 Trace of Chat Lifecycle Operations
- **Open Conversation (`loadConversation`):**
  - Marks conversation as read in Firestore transaction: sets `unreadCounts[currentUser.uid] = 0` (`SocialRepository.kt:218-234`).
  - Launches 2 unassigned coroutines in `viewModelScope` (`ChatViewModel.kt:30-50`):
    1. Loads conversation metadata and listens to `getUserProfileFlow(otherUserId)`.
    2. Listens to `getMessages(conversationId)`.
- **Send Message:**
  - Checks client restriction `UserSecurityManager.canChat()` (`ChatViewModel.kt:56`).
  - Calls `docRef.collection("messages").document()` to generate a document reference.
  - Inserts document into Firestore, then runs a transaction on parent conversation to increment other participants' `unreadCounts` and set `lastMessage`.
- **Edit Message:**
  - Updates Firestore document: `msgRef.update("text", newText, "isEdited", true)` (`SocialRepository.kt:335`).
- **Delete Message:**
  - If `forEveryone = true`: sets `msgRef.update("isDeleted", true)`. Text remains on the document, but `isDeleted` flag causes `ChatScreen` to show "Message deleted" (`ChatScreen.kt:369`).
  - If `forEveryone = false`: runs transaction adding `user.uid` to `deletedFor` array (`SocialRepository.kt:344-357`).
- **Reactions:**
  - Transaction toggles or sets `reactions[user.uid] = emoji` on Firestore message document (`SocialRepository.kt:363-377`).
- **Audio / Voice Messages:**
  - Records AAC audio to `cacheDir/audio_<timestamp>.m4a` using `MediaRecorder`.
  - Dispatches file to Cloudinary unsigned preset upload (`SocialRepository.kt:178`).
  - On upload completion, writes message with `isVoice = true` and `mediaUrl = cloudinarySecureUrl`.

### 3.2 Explicit Forensic Questionnaire

#### Q1: Can the same message be inserted twice?
**YES.**
In `SocialRepository.sendMessage()` (`SocialRepository.kt:261`):
```kotlin
val msgRef = docRef.collection("messages").document()
val msg = PrivateMessage(msgRef.id, user.uid, text, System.currentTimeMillis())
msgRef.set(msg)
```
Every invocation generates a fresh random Firestore document ID. There is no client message deduplication, no idempotent message key (such as `clientAssignedMessageId`), and no UI button debounce on the send touch event (`ChatScreen.kt:261-303`). Rapid tapping inserts identical messages with distinct document IDs.

#### Q2: Can retry create duplicate message?
**YES.**
Because document ID generation happens inside `sendMessage()` at call time, any failure retry invokes the method again, generating a second document reference.

#### Q3: Can message order become incorrect?
**YES.**
Firestore orders messages by client-generated timestamps:
`orderBy("timestamp", Query.Direction.ASCENDING)` (`SocialRepository.kt:208`).
The timestamp is assigned client-side: `System.currentTimeMillis()` (`SocialRepository.kt:262, 384`).
If the client's clock is inaccurate, skewed, or shifted by user settings, or if two messages are sent within the same millisecond, order between participants becomes non-deterministic and can invert message replies. (Server timestamp `FieldValue.serverTimestamp()` is not used for message ordering).

#### Q4: Can deleted messages remain in Room?
**NO for Chat, YES for Support.**
Private Chat messages are strictly memory-resident in `ChatViewModel._messages: StateFlow<List<PrivateMessage>>`. They are never written to Room.
However, for Support chat (`HelpSupportScreen`), messages **are** written to Room (`SupportDao`). If an admin deletes a message in Firestore, `SupportViewModel.kt:63-82` only iterates and inserts incoming documents; it never deletes missing documents from Room. Deleted support messages persist in Room indefinitely until the user manually triggers "Clear Chat" (`SupportViewModel.kt:148`).

#### Q5: Can a user see another user's private conversation?
**NO for normal users, YES for Admin users, and YES across users on a shared device for Support.**
- In Firestore security rules:
  ```firestore
  match /conversations/{conversationId} {
    allow read: if isAuthenticated() && (
      isAdmin() ||
      (resource != null && request.auth.uid in resource.data.participants)
    );
  ```
  Only conversation participants or admins can read `/conversations/{id}` and `/messages/{msgId}`. Normal users cannot read other conversations.
- However, for Support conversations (`/support_conversations/{uid}`), all messages are stored in local Room table `support_messages` without a `userId` column. If User A logs out and User B logs in on the same Android device, User B can view User A's complete support conversation history.

#### Q6: Can a chat listener survive navigation?
**NO across screens, but YES on duplicate `loadConversation()` calls.**
- In `ChatScreen.kt:80`, `ChatViewModel` is scoped to the `ChatScreen` composable via default `viewModel()`. When the user navigates back and the composable is removed from the backstack, `ChatViewModel` is cleared, cancelling `viewModelScope` and tearing down the `callbackFlow` listener (`awaitClose { listener.remove() }` in `SocialRepository.kt:215`).
- **However**, within the same `ChatViewModel`, if `loadConversation()` is invoked multiple times (e.g. from an intent parser or rapid route parameter change), previous collection coroutines are not cancelled (`ChatViewModel.kt:45-50`), creating concurrent listeners.

---

## 4. Stories Forensics

### 4.1 Investigation of Stories Architecture
1. **Data Model:**
   ```kotlin
   data class Story(
       val id: String = "",
       val userId: String = "",
       val imageUrl: String = "",
       val timestamp: Long = System.currentTimeMillis()
   )
   ```
2. **Backend Query:**
   ```kotlin
   fun getStories(): Flow<List<Story>> = callbackFlow {
       val listener = db.collection("stories")
           .orderBy("timestamp", Query.Direction.DESCENDING)
           .addSnapshotListener { snapshot, _ ->
               if (snapshot != null) {
                   val stories = snapshot.toObjects(Story::class.java)
                   trySend(stories)
               }
           }
       awaitClose { listener.remove() }
   }
   ```
3. **Forensic Findings in Stories Subsystem:**
   - **Unbounded Global Query:** The query fetches every story in the database across all users. There is no filter for the last 24 hours (e.g., `whereGreaterThan("timestamp", System.currentTimeMillis() - 86400000)`), nor a document limit (`limit(50)`).
   - **UI Disconnect & Dead End:** In `SocialScreen.kt:188`, the code contains:
     ```kotlin
     // For now, no actual stories are rendered until fetched, we just show add story
     ```
     The collected `stories` StateFlow (`SocialViewModel.stories`) is completely ignored by `SocialScreen`. The `LazyRow` only renders the "Add Story" icon.
   - **Feature Mocking:** Clicking "Add Story" triggers:
     ```kotlin
     Toast.makeText(context, context.getString(R.string.story_feature_coming_soon), Toast.LENGTH_SHORT).show()
     ```
     Users have no mechanism in the UI to upload or view stories.

---

## 5. Profiles & Social Graph Forensics

### 5.1 Simulated Watch History & Favorite Media
In `PublicProfileScreen.kt:116-150`:
```kotlin
// Deterministic selection based on userId
val seed = remember(userId) { kotlin.math.abs(userId.hashCode()) }

val moviesList = remember(trendingMovies, seed) {
    if (trendingMovies.isEmpty()) emptyList()
    else {
        val count = (8 + (seed % 9)).coerceAtMost(trendingMovies.size)
        trendingMovies.shuffled(java.util.Random(seed.toLong())).take(count).map {
            FriendMediaItem(
                id = it.id,
                title = it.title,
                posterUrl = it.posterUrl,
                isMovie = true,
                isAnime = false
            )
        }
    }
}
```
**Forensic Verdict:**
The public profile screen does not fetch the target user's actual watch history, library, or favorites from Firestore or any server backend. Instead, it takes the current user's local `trendingMovies` cache and randomly shuffles it using a pseudo-random seed derived from `userId.hashCode()`. The displayed stats ("Movies watched", "Series completed", "Recent activity") are purely synthetic mock data.

### 5.2 Client-Only Friends, Requests & Block Lists
1. **Friends:**
   - There is no `/users/{uid}/friends` subcollection. Friendship is implicitly inferred if a conversation exists in `/conversations` with both users as participants and `!it.isRequest` (`PublicProfileScreen.kt:85-87`).
2. **Friend Requests:**
   - Stored in local device DataStore:
     ```kotlin
     val friendRequests by userPrefs.friendRequests.collectAsState(initial = emptySet())
     ```
     When a user clicks "Add Friend", `userPrefs.sendFriendRequest(userId)` writes to `stringSetPreferencesKey("friend_requests")` on the local device. The other user **never receives** any notification or database entry indicating a friend request was sent.
3. **Blocked Users:**
   - Stored in local device DataStore:
     ```kotlin
     val blockedUsers by userPrefs.blockedUsers.collectAsState(initial = emptySet())
     ```
     When blocking a user, `userPrefs.blockUser(userId)` only records the UID locally.
   - **Firestore Security Rules Invalidation:**
     Firestore rules for `/conversations` and `/conversations/{id}/messages` contain zero verification against blocked users. A blocked user can continue to message the blocker in Firestore.

---

## 6. Support Ticket Forensics

### 6.1 Authentication vs Guest Separation
In `SupportViewModel.kt:86-134`:
- **Authenticated User:**
  - Messages are persisted locally to Room (`support_messages`).
  - Messages are pushed to Firestore:
    - Metadata: `/support_conversations/{user.uid}`
    - Message: `/support_conversations/{user.uid}/messages/{msgId}`
  - Listens in real time via Firestore snapshot listener on `/support_conversations/{user.uid}/messages` (`SupportViewModel.kt:52-83`).
- **Guest User:**
  - Messages are persisted locally to Room only.
  - A local heuristic bot generates instant auto-responses after a 1-second delay (`SupportViewModel.kt:128-146`):
    ```kotlin
    delay(1000)
    val response = generateResponse(text.trim())
    dao.insertMessage(SupportMessage(text = response, isFromUser = false))
    ```

### 6.2 Cross-User Data Leakage in Room
In `SupportMessage.kt:6-12`:
```kotlin
@Entity(tableName = "support_messages")
data class SupportMessage(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
```
The Room table has no `userId` column. When `AppDatabase.getDatabase(context).supportDao().getAllMessages()` emits, it emits all messages in the table regardless of who is currently logged in.
When a user signs out via `AuthRepository.signOut()`:
- `supportDao.clearMessages()` is **never called**.
- Any new user logging into the device immediately sees the previous user's private support chat.

---

## 7. Notifications & FCM Forensics

### 7.1 Architecture of Dual Notification Channels
The application maintains two independent mechanisms to deliver notifications:
1. **Firestore In-App Announcement Polling/Listening:**
   - Managed by `NotificationRepository.syncCloudNotifications()` and `NotificationRepository.listenForAnnouncements()`.
   - Queries `/notifications` with `limit(50)`.
   - Filters by `isActive`, `expiresAt`, `target = "all" | "user"`, and user preference categories.
   - Writes valid announcements to Room `notifications` table and triggers `NotificationHelper.showGeneralNotification()`.
2. **Push Firebase Cloud Messaging (FCM):**
   - Managed by `AppFirebaseMessagingService` (declared in `AndroidManifest.xml:63-69`).
   - Receives data messages in `onMessageReceived(remoteMessage)`.
   - Evaluates target validity (`FcmTargetValidator`), category preference permissions (`NotificationCategoryResolver`), and deduplication (`NotificationDeduplicator`).
   - Writes to Room `notifications` table and shows system tray notification.

### 7.2 Main-Thread / Callback Thread Blocking (`runBlocking`)
1. In `NotificationRepository.listenForAnnouncements()` (`NotificationRepository.kt:156-158`):
   ```kotlin
   val prefs = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
       NotificationPreferencesRepository(context).getPreferences()
   }
   ```
   This is executed directly inside the Firestore snapshot listener callback. Firestore dispatches snapshot callbacks on its own internal dispatcher; invoking `runBlocking` stalls the snapshot listener pipeline.
2. In `AppFirebaseMessagingService.kt:180, 207`:
   ```kotlin
   runBlocking(Dispatchers.IO) {
       NotificationPreferencesRepository(applicationContext).preferencesFlow.first()
   }
   ```
   and
   ```kotlin
   runBlocking(Dispatchers.IO) {
       val dao = daoOverride ?: AppDatabase.getDatabase(applicationContext).notificationDao()
       ...
       dao.insertNotification(payload.toNotificationItem())
   }
   ```
   `onMessageReceived` is called on an Android Binder thread pool. While running on a background binder thread avoids an immediate UI freeze, using `runBlocking` on binder worker threads can block the binder thread pool during high-frequency message bursts.

### 7.3 FCM Token Management & Multi-Device Isolation
In `FcmTokenManager.kt`:
- Persistent installation ID is stored in DataStore (`fcm_installation_id`).
- When a user signs in, token document is stored in Firestore at:
  `/users/{uid}/fcmTokens/{installationId}` (`FcmTokenManager.kt:139-144`).
- Document payload includes:
  `token`, `installationId`, `platform = "android"`, `deviceModel`, `osVersion`, `appVersion`, `isActive = true`, `createdAt`, `lastSeenAt`.
- On sign out:
  `/users/{oldUid}/fcmTokens/{installationId}` is deleted from Firestore (`FcmTokenManager.kt:177-192`).
- On unrecoverable delivery error (e.g. `UNREGISTERED`):
  Token is marked inactive with `deactivationReason` and `deactivatedAt` (`FcmTokenManager.kt:235-257`).
- **Verdict:** FCM Token Architecture is robust, multi-device compliant, and cleanly isolated per installation.

---

## 8. P2P / Nearby Devices / File Sharing Forensics

### 8.1 Tri-Discovery & Socket Architecture
`P2PManager` combines three discovery strategies concurrently:
1. Google Nearby Connections (`connectionsClient.startAdvertising`, `connectionsClient.startDiscovery` with `Strategy.P2P_STAR`).
2. Local UDP Multicast & Broadcast on port 8888 (`startUdpDiscovery`, `startUdpResponder`).
3. Active TCP Subnet Probe across all 254 IP addresses in the local `/24` subnet on port 8888 (`startSubnetProbeDiscovery`).

### 8.2 Critical Security Vulnerability: Unsanitized File Destination Path (Path Traversal)
In `P2PManager.kt:714`:
```kotlin
val destFile = MediaStorageUtils.getDestinationFile(context, id, extension)
```
In `MediaStorageUtils.kt:98-103`:
```kotlin
fun getDestinationFile(context: Context, id: String, extension: String? = null): File {
    val dir = getMediaDirectory(context)
    val cleanExt = extension?.trim()?.removePrefix(".")?.ifEmpty { null }
    val fileName = if (cleanExt != null) "${id}.${cleanExt}" else "${id}.mp4"
    return File(dir, fileName)
}
```
**Forensic Analysis:**
- The `id` parameter is transmitted over the raw TCP socket or Nearby Connections payload by the remote sender (`P2PManager.kt:660`).
- Neither `P2PManager` nor `MediaStorageUtils` sanitizes `id` against path traversal patterns (`../` or `/`).
- If an adversary on the local network connects and sends `id = "../../shared_prefs/user_prefs"`, the destination file resolves outside the `movies` directory and overwrites app private files upon transfer completion.

### 8.3 Unclosed Network Sockets & Thread Leaks
In `ShareScreen.kt:255-258`:
```kotlin
DisposableEffect(Unit) {
    ...
    onDispose {
        p2pManager.stopDiscovery()
    }
}
```
**Forensic Analysis:**
- `P2PManager.startBackgroundService()` binds a `ServerSocket` on port 8888 and starts a UDP listener on port 8888 (`P2PManager.kt:240-245, 529`).
- When navigating away from `ShareScreen`:
  - `p2pManager.stopDiscovery()` stops discovery only.
  - The `ServerSocket` on port 8888 **remains open and listening**.
  - The UDP responder **remains active**.
  - The Wi-Fi `MulticastLock` is **not released**.
  - The callback references `p2pManager.onMediaReceived`, `onMediaSent`, and `onDisconnected` are **never nulled**, retaining references to the composable's `CoroutineScope` and repositories.

### 8.4 UI Deadlock on Background Incoming Connections
In `P2PManager.kt:591-598`:
```kotlin
// 2. Trigger Confirmation Dialog on Receiver's UI
val decisionDeferred = CompletableDeferred<Boolean>()
connectionDecision = decisionDeferred
_pendingConnectionRequest.value = ConnectionRequest(clientIp, peerName, isSocket = true, ip = clientIp)

// Wait for user to explicitly click Accept or Decline
val accepted = decisionDeferred.await()
_pendingConnectionRequest.value = null
```
`_pendingConnectionRequest` is collected and displayed **only inside `ShareScreen.kt`**. If a peer connects while the user is watching a movie, browsing the home screen, or backgrounding the app:
- The dialog is never rendered to the user.
- `decisionDeferred.await()` hangs indefinitely.
- The incoming client socket connection thread is blocked indefinitely until the peer drops the connection.

---

## 9. Audio Recording / Audio Playback / Camera / QR Forensics

### 9.1 Audio Recording (`AudioRecorder.kt`)
- Uses `MediaRecorder` configured for MPEG-4 / AAC audio (`MediaRecorder.OutputFormat.MPEG_4`, `MediaRecorder.AudioEncoder.AAC`).
- Files are saved to `context.cacheDir/audio_<timestamp>.m4a`.
- Permission check `Manifest.permission.RECORD_AUDIO` is performed before invocation in `ChatScreen.kt:283`.
- `stopRecording()` safely calls `stop()`, `release()`, and sets `recorder = null` inside a try-catch block.
- **Finding:** Cleaned-up temporary audio files are not deleted after successful upload to Cloudinary, accumulating in `cacheDir`.

### 9.2 Audio Playback (`AudioPlayer.kt`)
- Implemented as a singleton `object AudioPlayer`.
- Wraps Android `MediaPlayer` with asynchronous preparation (`prepareAsync()`).
- Exposes `currentlyPlayingId: StateFlow<String?>`.
- `ChatScreen` includes a `DisposableEffect` that stops playback when navigating away:
  ```kotlin
  DisposableEffect(Unit) {
      onDispose {
          AudioPlayer.stop()
      }
  }
  ```
- **Finding:** Lifecycle-safe and correctly cleaned up.

### 9.3 Camera & QR Scanner (`QrCodeScannerDialog.kt`)
- Uses CameraX (`ProcessCameraProvider`, `Preview`, `ImageAnalysis`) with ZXing `MultiFormatReader` for barcode decoding.
- In `QrCodeScannerDialog.kt:86`:
  ```kotlin
  val cameraExecutor = Executors.newSingleThreadExecutor()
  ```
  This executor is instantiated inside the `AndroidView.factory` lambda without any lifecycle tracking.
- **Thread Leak:** When the dialog is dismissed (`onDismiss`), `cameraExecutor.shutdown()` is **never called**. Every open/close cycle of the QR scanner dialog permanently leaks a background OS thread.
- Furthermore, `cameraProvider.unbindAll()` is only invoked inside the future completion callback, not upon dialog dismissal.

### 9.4 QR Code Generator (`QRCodeGenerator.kt`)
- Uses ZXing `QRCodeWriter().encode()` to produce a 512x512 ARGB bitmap.
- Pure synchronous in-memory utility without persistent resources. Fully safe.

---

## 10. Privacy, Isolation & Security Rules Audit

### 10.1 Firestore Security Rules Verification

| Firestore Collection | Path | Read Rule | Write Rule | Security Invariant Evaluation |
|---|---|---|---|---|
| **Conversations** | `/conversations/{id}` | Authenticated && (isAdmin() \|\| auth.uid in participants) | Create: auth.uid in participants && canChat && !isBanned; Update: auth.uid in participants \|\| isAdmin() | **SECURE.** Only participants or admins can access conversation metadata. Ban enforcement verified. |
| **Messages** | `/conversations/{id}/messages/{msgId}` | Authenticated && (isAdmin() \|\| auth.uid in participants) | Create: senderId == auth.uid && auth.uid in participants && canChat && !isBanned; Update/Delete: senderId == auth.uid \|\| isAdmin() | **SECURE.** Messages can only be read/sent by participants. User can only edit/delete their own messages. |
| **Stories** | `/stories/{storyId}` | Authenticated | Create: userId == auth.uid && canStory && !isBanned; Update/Delete: userId == auth.uid \|\| isAdmin() | **PARTIALLY SECURE.** Anyone authenticated can read all stories globally. Creation requires owner match and canStory permission. |
| **Support Conversations** | `/support_conversations/{uid}` | Authenticated && (isAdmin() \|\| auth.uid == resource.data.userId) | Create/Update: auth.uid == resource.data.userId \|\| isAdmin() | **SECURE.** Normal users can only access their own support conversation. |
| **Support Messages** | `/support_conversations/{uid}/messages/{msgId}` | Authenticated && (isAdmin() \|\| auth.uid == parent.userId) | Create: auth.uid == parent.userId && senderId == auth.uid && senderRole == 'user' \|\| isAdmin() | **SECURE.** User cannot forge messages in another user's support ticket. |
| **Reports** | `/reports/{reportId}` | Authenticated && (isAdmin() \|\| auth.uid == resource.data.userId) | Create: auth.uid == request.resource.data.userId && status == 'pending'; Update/Delete: isAdmin() | **SECURE.** Users can only submit pending reports and view their own reports. |
| **FCM Tokens** | `/users/{uid}/fcmTokens/{instId}` | Authenticated && (isAdmin() \|\| isOwner(uid)) | Create/Update/Delete: isOwner(uid) \|\| isAdmin() | **SECURE.** Users can only write/delete tokens in their own user subcollection. |
| **Friends / Blocks** | N/A | No rules exist | No rules exist | **INSECURE ARCHITECTURAL GAP.** Friends and Blocks are not managed in Firestore. Blocked users can continue to chat. |

---

## 11. Lifecycle & Resource Leak Audit

| Component | File & Line | Resource Involved | Expected Cleanup | Actual Lifecycle Handling | Leak Severity |
|---|---|---|---|---|---|
| `SocialViewModel` | `SocialViewModel.kt:71-76` | Firestore Snapshot Listener | Cancelled on `stopListening()` | `conversationJob` set to delay job; `repo.getConversations()` collection coroutine never cancelled | **HIGH** (Accumulates duplicate snapshot listeners) |
| `ChatViewModel` | `ChatViewModel.kt:45-50` | Firestore Snapshot Listener | Cancelled if `loadConversation` re-invoked | Previous `repo.getMessages` collection job not cancelled before launching new one | **MEDIUM** (Duplicate message collection if reloaded) |
| `ShareScreen` / `P2PManager` | `ShareScreen.kt:255-258` | Java `ServerSocket(8888)`, UDP Datagram, MulticastLock | Stopped on `onDispose` | `onDispose` calls only `p2pManager.stopDiscovery()`; ServerSocket and UDP responder remain active | **HIGH** (Battery drain, background port listening) |
| `ShareScreen` | `ShareScreen.kt:205-253` | Lambdas capturing `CoroutineScope` | Cleared on `onDispose` | `p2pManager.onMediaReceived` and `onMediaSent` never nulled | **MEDIUM** (Retains references to disposed composable context) |
| `QrCodeScannerDialog` | `QrCodeScannerDialog.kt:86` | Java `ExecutorService` (SingleThreadExecutor) | `shutdown()` on dialog dismiss | Never shut down | **HIGH** (Leaks active OS thread per dialog display) |
| `NotificationRepository` | `NotificationRepository.kt:102, 138` | Firestore Snapshot Listener | Removed on `stopListeningAnnouncements()` | Static companion `listenerRegistration` reused, but `runBlocking` in listener stalls dispatch thread | **MEDIUM** (Thread contention) |
| `SupportViewModel` | `SupportViewModel.kt:160-164` | Firestore Snapshot Listener | Removed in `onCleared()` | Properly removed via `listenerRegistration?.remove()` in `onCleared()` | **PASS** |
| `ChatScreen` | `ChatScreen.kt:97-101` | Android `MediaPlayer` | Stopped on `onDispose` | Properly stopped via `AudioPlayer.stop()` in `onDispose` | **PASS** |

---

## 12. Comprehensive Findings Matrix & Remediation Blueprint

| ID | Domain | Component | Severity | Description & Root Cause | Recommended Remediation (For Future Execution) |
|---|---|---|---|---|---|
| **SOC-06-01** | P2P | `MediaStorageUtils.kt:98-103` | **CRITICAL** | Path Traversal Vulnerability: Remote sender provides raw `id` which is directly concatenated into destination filename without sanitizing against `../` or `/`. | Sanitize `id` via `File(id).name` or alphanumeric regex validation before constructing `destFile`. |
| **SOC-06-02** | P2P | `ShareScreen.kt:255-258`, `P2PManager.kt:529` | **HIGH** | Resource Leak: `ServerSocket(8888)`, UDP listener, and `MulticastLock` continue running after leaving `ShareScreen`. | Call `p2pManager.stopBackgroundService()` or `stopAll()` in `ShareScreen.onDispose`. |
| **SOC-06-03** | P2P | `P2PManager.kt:591-598` | **HIGH** | Connection Deadlock: If remote peer connects while user is off `ShareScreen`, `decisionDeferred.await()` hangs indefinitely. | Automatically reject incoming connections or apply a 15-second timeout if `ShareScreen` is not active. |
| **SOC-06-04** | Social | `SocialViewModel.kt:67-76` | **HIGH** | Listener Leak: `conversationJob` is assigned to a delay job while `repo.getConversations()` is launched in an unassigned coroutine. | Store the collection job in `conversationJob` and cancel it on `stopListening()`. |
| **SOC-06-05** | Chat | `ChatScreen.kt:327` | **HIGH** | List Recomposition Jank & Allocations: `items(messages.reversed())` allocates a new list every recomposition and lacks a `key = { it.id }`. | Provide `key = { it.id }` to `items()` and compute reversed list via `remember(messages)`. |
| **SOC-06-06** | Social Graph | `PublicProfileScreen.kt:117-148` | **HIGH** | Simulated Data: Public user's watch history and favorites are faked using `Random(userId.hashCode())` on trending movies. | Replace with genuine Firestore `/users/{uid}/history` or explicitly show "Profile activity private". |
| **SOC-06-07** | Social Graph | `UserPreferencesRepository.kt:37-60` | **HIGH** | Local-Only Friends & Blocks: Blocking a user or sending friend requests is stored in DataStore on the local device only. | Implement cloud synchronization under `/users/{uid}/blocks` and enforce block checks in Firestore rules. |
| **SOC-06-08** | Stories | `SocialScreen.kt:188`, `SocialRepository.kt:108` | **MEDIUM** | Dead-End UI & Unbounded Query: Stories query fetches all stories globally without a 24h threshold; UI suppresses stories list and shows "coming soon" toast. | Add `timestamp >= (now - 24h)` and `limit(50)` to Firestore query, and implement story feed in `SocialScreen`. |
| **SOC-06-09** | Support | `SupportMessage.kt:6-12`, `SupportViewModel.kt:30` | **MEDIUM** | Cross-User Support Chat Leak: Room table `support_messages` has no `userId` partition, exposing previous user's chat on sign-out/sign-in. | Add `userId: String` column to `SupportMessage` entity and filter by current user, or clear table on sign-out. |
| **SOC-06-10** | Camera / QR | `QrCodeScannerDialog.kt:86` | **MEDIUM** | Thread Leak: `Executors.newSingleThreadExecutor()` is created inside `AndroidView.factory` and never shut down. | Manage `cameraExecutor` with `remember { Executors.newSingleThreadExecutor() }` and call `shutdown()` in `DisposableEffect`. |
| **SOC-06-11** | Notifications | `NotificationRepository.kt:156`, `AppFirebaseMessagingService.kt:180` | **MEDIUM** | Thread Blocking: `runBlocking(Dispatchers.IO)` invoked inside Firestore snapshot listener callback and FCM binder service. | Refactor to asynchronous coroutine scopes or cached preference reads without blocking dispatch threads. |
| **SOC-06-12** | Chat | `SocialRepository.kt:261` | **MEDIUM** | Non-Idempotent Message Creation: Messages generate random document IDs at call time, allowing duplicates on double-tap or retry. | Implement client-generated message IDs and debounce send button clicks in `ChatScreen`. |
| **SOC-06-13** | Notifications | `TransferNotificationHelper.kt:40` | **LOW** | Notification Icon Styling: Uses `R.mipmap.ic_launcher` instead of monochrome `R.drawable.ic_notification`. | Update notification small icon to `R.drawable.ic_notification` to comply with Android notification guidelines. |

---

## 13. Hard Stop & Compliance Confirmation

- **Zero modifications** were applied to production source files, test code, Gradle configurations, dependencies, Firebase configurations, or Room databases.
- The forensic audit was strictly read-only and derived purely from static analysis of the uploaded project source code.
- Report complete and saved to `/PHASES/PHASE_06.6_SOCIAL_CHAT_STORIES_SUPPORT_NOTIFICATIONS_P2P_FORENSIC_AUDIT_REPORT.md`.
