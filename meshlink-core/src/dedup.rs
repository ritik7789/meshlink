use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{SystemTime, Duration};

#[derive(uniffi::Object)]
pub struct DedupCache {
    seen_messages: Mutex<HashMap<String, SystemTime>>,
}

#[uniffi::export]
impl DedupCache {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            seen_messages: Mutex::new(HashMap::new()),
        }
    }

    pub fn is_duplicate(&self, message_id: &str) -> bool {
        let lock = self.seen_messages.lock().unwrap();
        lock.contains_key(message_id)
    }

    pub fn record_message(&self, message_id: &str) {
        let mut lock = self.seen_messages.lock().unwrap();
        lock.insert(message_id.to_string(), SystemTime::now());
    }

    pub fn cleanup_expired(&self, max_age_secs: u32) {
        let mut lock = self.seen_messages.lock().unwrap();
        let now = SystemTime::now();
        let max_age = Duration::from_secs(max_age_secs as u64);

        lock.retain(|_, time_seen| {
            if let Ok(elapsed) = now.duration_since(*time_seen) {
                elapsed < max_age
            } else {
                false // If time went backwards or something, keep it or throw it away? Throw away.
            }
        });
    }
}

impl Default for DedupCache {
    fn default() -> Self {
        Self::new()
    }
}
