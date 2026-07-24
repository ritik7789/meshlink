pub const PROTOCOL_VERSION: u8 = 1;

pub fn protocol_version() -> u8 {
    PROTOCOL_VERSION
}

pub fn is_protocol_compatible(remote_version: u8) -> bool {
    remote_version == PROTOCOL_VERSION
}
