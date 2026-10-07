//! The backend contract suite on the real backend. The cases are
//! `apps/editor/src/core/backend/contract.json`, which `contract.test.ts`
//! also runs against `MemoryBackend`, so the fake the UI's tests use can't
//! promise anything these commands don't do.
//!
//! Each step goes through Tauri's own IPC on its mock runtime
//! (`tauri::test::get_ipc_response`), with the arguments as the UI sends them:
//! the generated invoke handler, argument deserialization, the managed
//! `AppState` and the serialized `{ code, message }` rejection are all the
//! app's. Only the window is fake.

use std::path::Path;
use std::sync::Arc;

use serde_json::{Value, json};
use tauri::ipc::{CallbackFn, InvokeBody};
use tauri::test::{INVOKE_KEY, MockRuntime, get_ipc_response, mock_builder};
use tauri::webview::InvokeRequest;
use tauri::{App, Manager, WebviewWindow, WebviewWindowBuilder};

use super::bindings;
use crate::app::dirs::AppDirs;
use crate::app::events::EventSink;
use crate::state::AppState;

const CASES: &str = include_str!("../../../src/core/backend/contract.json");

/// The editor's backend on the mock runtime, with its folders in [dir].
fn app(dir: &Path) -> (App<MockRuntime>, WebviewWindow<MockRuntime>) {
    let builder = bindings::mock_builder();
    let app = mock_builder()
        .invoke_handler(builder.invoke_handler())
        .build(crate::context())
        .expect("the mock app builds");
    let sink: Arc<dyn EventSink> = Arc::new(app.handle().clone());
    let dirs = AppDirs::in_one(dir);
    let tools = crate::app::tools::Tools::for_app(None, &dirs);
    app.manage(AppState::new(dirs, tools, sink));
    let window = WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("the mock window opens");
    (app, window)
}

/// Calls [command] as the UI would: `Ok(answer)` or `Err(rejection)`.
fn call(window: &WebviewWindow<MockRuntime>, command: &str, args: Value) -> Result<Value, Value> {
    let request = InvokeRequest {
        cmd: command.into(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        // The app's own origin, so the call is checked against its capability.
        url: if cfg!(windows) {
            "http://tauri.localhost"
        } else {
            "tauri://localhost"
        }
        .parse()
        .unwrap(),
        body: InvokeBody::Json(args),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.into(),
    };
    get_ipc_response(window, request).map(|body| body.deserialize::<Value>().unwrap())
}

/// Replaces `$ROOT`, `$GIT` and each saved `$<name>` in every string.
fn substitute(value: &Value, vars: &[(String, String)]) -> Value {
    match value {
        Value::String(s) => Value::String(vars.iter().fold(s.clone(), |text, (name, it)| {
            text.replace(name.as_str(), it)
        })),
        Value::Array(items) => items.iter().map(|it| substitute(it, vars)).collect(),
        Value::Object(map) => map
            .iter()
            .map(|(k, v)| (k.clone(), substitute(v, vars)))
            .collect::<serde_json::Map<_, _>>()
            .into(),
        other => other.clone(),
    }
}

/// Runs git in [cwd] as a test author.
fn git(cwd: &Path, args: &[&str]) {
    let output = std::process::Command::new("git")
        .args(["-c", "user.name=Test", "-c", "user.email=test@example.com"])
        .args(args)
        .current_dir(cwd)
        .output()
        .expect("git runs");
    assert!(
        output.status.success(),
        "git {args:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

/// [files] committed on main and tagged v1, pushed to a bare repository at
/// `<dir>/<name>`: what `$GIT/<name>` names.
fn publish(dir: &Path, name: &str, files: &serde_json::Map<String, Value>) {
    let work = dir.join(format!("{name}-work"));
    for (path, text) in files {
        let file = work.join(path);
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, text.as_str().unwrap()).unwrap();
    }
    git(&work, &["init", "--quiet", "--initial-branch=main"]);
    git(&work, &["add", "-A"]);
    git(&work, &["commit", "--quiet", "-m", name]);
    git(&work, &["tag", "v1"]);
    git(
        dir,
        &["clone", "--quiet", "--bare", &format!("{name}-work"), name],
    );
}

/// [actual] has everything [expected] says; `"$any"` matches anything.
fn matches(expected: &Value, actual: &Value) -> bool {
    match (expected, actual) {
        (Value::String(s), _) if s == "$any" => true,
        (Value::Object(want), Value::Object(got)) => want
            .iter()
            .all(|(key, value)| got.get(key).is_some_and(|it| matches(value, it))),
        (Value::Array(want), Value::Array(got)) => {
            want.len() == got.len() && want.iter().zip(got).all(|(w, g)| matches(w, g))
        }
        (want, got) => want == got,
    }
}

#[test]
fn the_rust_backend_keeps_the_contract() {
    let suite: Value = serde_json::from_str(CASES).unwrap();
    let cases = suite["cases"].as_array().unwrap();
    assert!(cases.len() > 10, "found only {} cases", cases.len());
    let commands = bindings::command_names();
    let mut failures = Vec::new();

    for case in cases {
        let name = case["name"].as_str().unwrap();
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        for (path, text) in suite["project"].as_object().unwrap() {
            let file = project.join(path);
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(file, text.as_str().unwrap()).unwrap();
        }
        for (name, files) in suite["packages"].as_object().unwrap() {
            for (path, text) in files.as_object().unwrap() {
                let file = temp.path().join(name).join(path);
                std::fs::create_dir_all(file.parent().unwrap()).unwrap();
                std::fs::write(file, text.as_str().unwrap()).unwrap();
            }
        }
        let repositories = temp.path().join("git");
        std::fs::create_dir_all(&repositories).unwrap();
        for (name, files) in suite["repositories"].as_object().unwrap() {
            publish(&repositories, name, files.as_object().unwrap());
        }
        let root = dunce::canonicalize(&project).unwrap();
        let root = root.to_str().unwrap();
        let git_url = format!(
            "file://{}{}",
            if cfg!(windows) { "/" } else { "" },
            repositories.to_str().unwrap().replace('\\', "/")
        );
        let mut vars = vec![
            ("$ROOT".to_string(), root.to_string()),
            ("$GIT".to_string(), git_url),
        ];
        let (_app, window) = app(&temp.path().join("app"));
        if case["open"] != json!(false) {
            call(&window, "project_open", json!({ "root": root })).expect("the project opens");
        }

        for (index, step) in case["steps"].as_array().unwrap().iter().enumerate() {
            let command = step["call"].as_str().unwrap();
            assert!(
                commands.iter().any(|it| it == command),
                "{name}: no command {command}"
            );
            let args = substitute(step.get("args").unwrap_or(&json!({})), &vars);
            let result = call(&window, command, args);
            let at = format!("{name}, step {} ({command})", index + 1);
            if let (Some(save), Ok(Value::String(got))) = (step["save"].as_str(), &result) {
                vars.push((format!("${save}"), got.clone()));
            }
            match (step.get("ok"), step.get("error"), result) {
                (Some(want), _, Ok(got)) if !matches(&substitute(want, &vars), &got) => {
                    failures.push(format!("{at}: expected {want}, got {got}"))
                }
                (Some(_), _, Ok(_)) => {}
                (_, Some(code), Err(error)) if &error["code"] != code => {
                    failures.push(format!("{at}: expected error {code}, got {error}"))
                }
                (_, Some(_), Err(_)) => {}
                (_, _, Ok(got)) => failures.push(format!("{at}: expected an error, got {got}")),
                (_, _, Err(error)) => failures.push(format!("{at}: failed with {error}")),
            }
        }
    }
    assert!(failures.is_empty(), "\n{}", failures.join("\n"));
}

#[test]
fn rejections_carry_a_code_and_a_message() {
    let temp = tempfile::tempdir().unwrap();
    let (_app, window) = app(temp.path());
    let error = call(&window, "fs_list", json!({})).unwrap_err();
    assert_eq!(error["code"], "noProject");
    assert_eq!(error["message"], "No project is open");
}
