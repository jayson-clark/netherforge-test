/// The app's commands get their permission from `permissions/generated/commands.toml`,
/// which `cargo run --example bindings` derives from the command list in
/// `src/commands/bindings.rs`; `capabilities/default.json` grants it.
fn main() {
    tauri_build::build();
}
