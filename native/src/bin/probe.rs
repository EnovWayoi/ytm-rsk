use std::io::{self, BufRead};

fn main() {
    for line in io::stdin().lock().lines() {
        let output = line.map_err(|e| e.to_string())
            .and_then(|line| serde_json::from_str(&line).map_err(|e| e.to_string()))
            .and_then(ytm_core::dispatch);
        println!("{}", match output { Ok(v) => serde_json::json!({"data":v}), Err(e) => serde_json::json!({"error":e}) });
    }
}
