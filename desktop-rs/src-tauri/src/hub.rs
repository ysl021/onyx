//! LAN hub (TCP 8787 + UDP 8788), ported from
//! `desktop/src/main/kotlin/app/onyx/desktop/hub/HubServer.kt`.
//!
//! TODO: tokio listener with `/health`, `/presence`, `/sync` and the pairing
//! code. The JSON schema must stay byte-compatible with the Android client.
