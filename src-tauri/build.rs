fn main() {
    // رقم البناء يأتي من GitHub Actions (github.run_number)، وإلا "dev" محلياً.
    let build = std::env::var("TAR_BUILD").unwrap_or_else(|_| "dev".to_string());
    println!("cargo:rustc-env=TAR_BUILD={build}");
    println!("cargo:rerun-if-env-changed=TAR_BUILD");
    tauri_build::build()
}
