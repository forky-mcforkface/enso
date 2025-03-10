//! This crate implements code formatter rules that are not implemented in rustfmt. These rules
//! are this codebase specific, and they may not be desired in other code bases, including:
//! - Sorting imports into groups (e.g. local imports, pub imports, etc.).
//! - Sorting module attributes into groups.
//! - Adding standard lint configuration to `lib.rs` and `main.rs` files.
//! - (Currently disabled) Emitting warnings about star imports that are not ending with `traits::*`
//!   nor `prelude::*`.
//!
//! Possible extensions, not implemented yet:
//! - Sections are automatically keeping spacing.

// === Features ===
#![feature(exit_status_error)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
// === Non-Standard Linter Configuration ===
#![deny(keyword_idents)]
#![deny(macro_use_extern_crate)]
#![deny(missing_abi)]
#![deny(pointer_structural_match)]
#![deny(unsafe_op_in_unsafe_fn)]
#![deny(unconditional_recursion)]
#![warn(missing_docs)]
#![warn(absolute_paths_not_starting_with_crate)]
#![warn(elided_lifetimes_in_paths)]
#![warn(explicit_outlives_requirements)]
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(noop_method_call)]
#![warn(single_use_lifetimes)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unused_crate_dependencies)]
#![warn(unused_extern_crates)]
#![warn(unused_import_braces)]
#![warn(unused_lifetimes)]
#![warn(unused_qualifications)]
#![warn(variant_size_differences)]
#![warn(unreachable_pub)]

use lazy_static::lazy_static;
use regex::Regex;
use std::collections::hash_map::DefaultHasher;
use std::collections::HashMap;
use std::ffi::OsStr;
use std::fmt::Debug;
use std::fs;
use std::hash::Hash;
use std::hash::Hasher;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::process::Stdio;



// =================
// === Constants ===
// =================

// TODO: The below lints should be uncommented, one-by-one, and the existing code should be
//       adjusted.

/// Standard linter configuration. It will be used in every `main.rs` and `lib.rs` file in the
/// codebase.
const STD_LINTER_ATTRIBS: &[&str] = &[
    // Rustc lints that are allowed by default:
    // "warn(absolute_paths_not_starting_with_crate)",
    // "warn(elided_lifetimes_in_paths)",
    // "warn(explicit_outlives_requirements)",
    // "deny(keyword_idents)",
    // "deny(macro_use_extern_crate)",
    // "deny(missing_abi)",
    // "warn(missing_copy_implementations)",
    // "warn(missing_debug_implementations)",
    // "warn(missing_docs)",
    "deny(non_ascii_idents)",
    // "warn(noop_method_call)",
    // "deny(pointer_structural_match)",
    // "warn(single_use_lifetimes)",
    // "warn(trivial_casts)",
    // "warn(trivial_numeric_casts)",
    "warn(unsafe_code)",
    // "deny(unsafe_op_in_unsafe_fn)",
    // "warn(unused_crate_dependencies)",
    // "warn(unused_extern_crates)",
    // "warn(unused_import_braces)",
    // "warn(unused_lifetimes)",
    // "warn(unused_qualifications)",
    // "warn(variant_size_differences)",
    // Rustc lints that emit a warning by default:
    // "deny(unconditional_recursion)",
];



// =============
// === Utils ===
// =============

fn calculate_hash<T: Hash>(t: &T) -> u64 {
    let mut s = DefaultHasher::new();
    t.hash(&mut s);
    s.finish()
}

fn read_file_with_hash(path: impl AsRef<Path>) -> std::io::Result<(u64, String)> {
    fs::read_to_string(path).map(|t| (calculate_hash(&t), t))
}



// ===================
// === HeaderToken ===
// ===================

use HeaderToken::*;

/// A token that can be found in the header of a file.
#[derive(Debug, Copy, Clone, PartialEq, Eq, Hash)]
#[allow(missing_docs)]
pub enum HeaderToken {
    Attrib,
    ModuleAttrib,
    ModuleAttribWarn,
    ModuleAttribAllow,
    ModuleAttribDeny,
    ModuleAttribFeature,
    ModuleAttribAllowIncFeat,
    EmptyLine,
    ModuleDoc,
    Comment,
    CrateUse,
    CrateUseStar,
    CratePubUse,
    CratePubUseStar,
    Use,
    UseStar,
    PubUse,
    PubUseStar,
    PubMod,
    /// Special header token that is never parsed, but can be injected by the code.
    ModuleComment,
    StandardLinterConfig,
}

/// A header token with the matched string and possibly attached attributes.
#[derive(Clone)]
pub struct HeaderElement {
    attrs:     Vec<String>,
    token:     HeaderToken,
    reg_match: String,
}

impl Debug for HeaderElement {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}({:?})", self.token, self.reg_match.as_str())
    }
}

impl HeaderElement {
    /// Constructor.
    pub fn new(token: HeaderToken, reg_match: String) -> Self {
        let attrs = Default::default();
        Self { attrs, token, reg_match }
    }

    /// Check whether the element is empty.
    pub fn is_empty(&self) -> bool {
        self.reg_match.is_empty()
    }

    /// Length of the splice. Includes the length of the matched string and all attached attributes.
    pub fn len(&self) -> usize {
        let args_len: usize = self.attrs.iter().map(|t| t.len()).sum();
        self.reg_match.len() + args_len
    }

    /// Convert the element to a string representation.
    #[allow(clippy::inherent_to_string)]
    pub fn to_string(&self) -> String {
        format!("{}{}", self.attrs.join(""), self.reg_match)
    }
}

/// Regex constructor that starts on the beginning of a line, can be surrounded by whitespaces and
/// ends with a line break.
fn header_line_regex(input: &str) -> Regex {
    let str = format!(r"^ *{} *(; *)?((\r\n?)|\n)", input);
    Regex::new(&str).unwrap()
}

macro_rules! define_rules {
    ($($name:ident = $re:tt;)*) => {
        #[allow(non_upper_case_globals)]
        mod static_re {
            use super::*;
            lazy_static! {
                $(
                    pub static ref $name: Regex = header_line_regex($re);
                )*
            }
        }

        fn match_header(input: &str) -> Option<HeaderElement> {
            $(
                if let Some(str) = static_re::$name.find(input) {
                    return Some(HeaderElement::new($name, str.as_str().into()));
                }
            )*
            None
        }
    };
}

define_rules! {
    EmptyLine                = r"";
    ModuleDoc                = r"//![^\n\r]*";
    Comment                  = r"//[^\n\r]*";
    CrateUse                 = r"use +crate( *:: *[\w]+)*( +as +[\w]+)?";
    CrateUseStar             = r"use +crate( *:: *[\w*]+)*";
    CratePubUse              = r"pub +use +crate( *:: *[\w]+)*( +as +[\w]+)?";
    CratePubUseStar          = r"pub +use +crate( *:: *[\w*]+)*";
    Use                      = r"use +[\w]+( *:: *[\w]+)*( +as +[\w]+)?";
    UseStar                  = r"use +[\w]+( *:: *[\w*]+)*";
    PubUse                   = r"pub +use +[\w]+( *:: *[\w]+)*( +as +[\w]+)?";
    PubUseStar               = r"pub +use +[\w]+( *:: *[\w*]+)*";
    ModuleAttribFeature      = r"#!\[feature[^\]]*\]";
    ModuleAttribAllowIncFeat = r"#!\[allow\(incomplete_features\)\]";
    ModuleAttribWarn         = r"#!\[warn[^\]]*\]";
    ModuleAttribAllow        = r"#!\[allow[^\]]*\]";
    ModuleAttribDeny         = r"#!\[deny[^\]]*\]";
    ModuleAttrib             = r"#!\[[^\]]*\]";
    Attrib                   = r"#\[[^\]]*\]";
    PubMod                   = r"pub +mod +[\w]+";
}



// =======================
// === Pretty printing ===
// =======================

/// Prints H1 section if any of the provided tokens was used in the file being formatted.
fn print_h1(
    out: &mut String,
    map: &HashMap<HeaderToken, Vec<String>>,
    tokens: &[HeaderToken],
    str: &str,
) {
    if tokens.iter().any(|tok| map.contains_key(tok)) {
        out.push('\n');
        out.push_str(&format!("// ===={}====\n", "=".repeat(str.len())));
        out.push_str(&format!("// === {} ===\n", str));
        out.push_str(&format!("// ===={}====\n", "=".repeat(str.len())));
        out.push('\n');
    }
}

/// Prints H2 section if any of the provided tokens was used in the file being formatted.
fn print_h2(
    out: &mut String,
    map: &HashMap<HeaderToken, Vec<String>>,
    tokens: &[HeaderToken],
    str: &str,
) {
    if tokens.iter().map(|tok| map.contains_key(tok)).any(|t| t) {
        out.push_str(&format!("// === {} ===\n", str));
    }
}

/// Prints all the entries associated with the provided tokens. If at least one entry was printed,
/// an empty line will be added in the end.
fn print(out: &mut String, map: &mut HashMap<HeaderToken, Vec<String>>, t: &[HeaderToken]) -> bool {
    // We collect the results because we want all tokens to be printed.
    let sub_results: Vec<bool> = t.iter().map(|t| print_single(out, map, *t)).collect();
    sub_results.iter().any(|t| *t)
}

/// Prints all the entries associated with the provided tokens. If at least one entry was printed,
/// an empty line will be added in the end.
fn print_section(out: &mut String, map: &mut HashMap<HeaderToken, Vec<String>>, t: &[HeaderToken]) {
    if print(out, map, t) {
        out.push('\n');
    }
}

/// Print all the entries associated with the provided token.
fn print_single(
    out: &mut String,
    map: &mut HashMap<HeaderToken, Vec<String>>,
    token: HeaderToken,
) -> bool {
    match map.remove(&token) {
        None => false,
        Some(t) => {
            out.push_str(&t.join(""));
            true
        }
    }
}



// ==============
// === Action ===
// ==============

/// Possible commands this formatter can evaluate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(missing_docs)]
pub enum Action {
    Format,
    DryRun,
    FormatAndCheck,
}



// ==================
// === Processing ===
// ==================

/// A path to rust source annottated with information whether it is a main or a library main source
/// file.
#[derive(Clone, Debug)]
#[allow(missing_docs)]
pub struct RustSourcePath {
    path:    PathBuf,
    is_main: bool,
}

/// Process all files of the given path recursively.
///
/// Please note that the [`hash_map`] variable contains hashes of all files before processing. After
/// the processing is done by this formatter, `rustfmt` is run on the whole codebase, and the hashes
/// are compared with new files. This allows checking if running the formatting changed the files.
/// An alternative design is possible – we could run this formatter and pass its output to stdin of
/// `rustfmt`, run it in memory, and get the results without affecting files on the disk.
/// Unfortunately, such solution requires either running a separate `rustfmt` process per file, or
/// using its API. The former solution is very slow (16 seconds for the whole codebase), the second
/// uses non-documented API and is slow as well (8 seconds for the whole codebase). It should be
/// possible to improve the latter solution to get good performance, but it seems way harder than it
/// should be.
fn process_path(path: impl AsRef<Path>, action: Action) {
    let paths = discover_paths(path);
    let total = paths.len();
    let mut hash_map = HashMap::<PathBuf, u64>::new();
    for (i, sub_path) in paths.iter().enumerate() {
        let dbg_msg = if sub_path.is_main { " [main]" } else { "" };
        println!("[{}/{}] Processing {}{}.", i + 1, total, sub_path.path.display(), dbg_msg);
        let hash = process_file(&sub_path.path, action, sub_path.is_main);
        hash_map.insert((&sub_path.path).into(), hash);
    }
    if action == Action::Format || action == Action::FormatAndCheck {
        Command::new("cargo")
            .arg("fmt")
            .stdin(Stdio::null())
            .status()
            .expect("'cargo fmt' failed to start.")
            .exit_ok()
            .unwrap();
    }

    if action == Action::FormatAndCheck {
        let mut changed = Vec::new();
        for sub_path in &paths {
            let (hash, _) = read_file_with_hash(&sub_path.path).unwrap();
            if hash_map.get(&sub_path.path) != Some(&hash) {
                changed.push(sub_path.path.clone());
            }
        }
        if !changed.is_empty() {
            panic!("{} files changed:\n{:#?}", changed.len(), changed);
        }
    }
}

/// Discover all paths containing Rust sources, recursively.
fn discover_paths(path: impl AsRef<Path>) -> Vec<RustSourcePath> {
    let mut vec = Vec::default();
    discover_paths_internal(&mut vec, path, false);
    vec
}

fn discover_paths_internal(
    vec: &mut Vec<RustSourcePath>,
    path: impl AsRef<Path>,
    is_main_dir: bool,
) {
    let path = path.as_ref();
    let md = fs::metadata(path);
    let md = md.unwrap_or_else(|_| panic!("Could get metadata of {}", path.display()));
    if md.is_dir() && path.file_name() != Some(OsStr::new("target")) {
        let dir_name = path.file_name();
        // FIXME: This should cover 'tests' folder also, but only the files that contain actual
        //        tests. Otherwise, not all attributes are allowed there.
        let is_main_dir = dir_name == Some(OsStr::new("bin")); // || dir_name == Some(OsStr::new("tests"));
        let sub_paths = fs::read_dir(path).unwrap();
        for sub_path in sub_paths {
            discover_paths_internal(vec, &sub_path.unwrap().path(), is_main_dir)
        }
    } else if md.is_file() && path.extension() == Some(OsStr::new("rs")) {
        let file_name = path.file_name().and_then(|s| s.to_str());
        let is_main_file = file_name == Some("lib.rs") || file_name == Some("main.rs");
        let is_main = is_main_file || is_main_dir;
        let path = path.into();
        vec.push(RustSourcePath { path, is_main });
    }
}

fn process_file(path: impl AsRef<Path>, action: Action, is_main_file: bool) -> u64 {
    let path = path.as_ref();
    let (hash, input) = read_file_with_hash(path).unwrap();

    match process_file_content(input, is_main_file) {
        Err(e) => panic!("{:?}: {}", path, e),
        Ok(out) => {
            if action == Action::DryRun {
                println!("{}", out)
            } else if action == Action::Format || action == Action::FormatAndCheck {
                fs::write(path, out).expect("Unable to write back to the source file.")
            }
            hash
        }
    }
}

/// Process a single source file.
fn process_file_content(input: String, is_main_file: bool) -> Result<String, String> {
    let mut str_ptr: &str = &input;
    let mut attrs = vec![];
    let mut header = vec![];
    loop {
        match match_header(str_ptr) {
            None => break,
            Some(mut m) => {
                str_ptr = &str_ptr[m.len()..];
                match m.token {
                    Attrib => attrs.push(m),
                    _ => {
                        if !attrs.is_empty() {
                            let old_attrs = std::mem::take(&mut attrs);
                            m.attrs = old_attrs.into_iter().map(|t| t.reg_match).collect();
                        }
                        header.push(m)
                    }
                }
            }
        }
    }

    // Do not consume the trailing comments.
    let mut ending: Vec<&HeaderElement> = header
        .iter()
        .rev()
        .take_while(|t| (t.token == Comment) || (t.token == EmptyLine))
        .collect();
    ending.reverse();
    let incorrect_ending_len = ending.into_iter().skip_while(|t| t.token == EmptyLine).count();
    header.truncate(header.len() - incorrect_ending_len);
    let total_len: usize = header.iter().map(|t| t.len()).sum();

    // Mark comments before any definitions as module comments.
    header
        .iter_mut()
        .take_while(|t| (t.token == Comment) || (t.token == EmptyLine) || (t.token == ModuleDoc))
        .map(|t| {
            if t.token == Comment && !t.reg_match.starts_with("// ===") {
                t.token = ModuleComment;
            }
        })
        .for_each(drop);

    // Error if the import section contains comments.
    let contains_comments =
        header.iter().find(|t| t.token == Comment && !t.reg_match.starts_with("// ==="));
    if let Some(comment) = contains_comments {
        return Err(format!(
            "File contains comments in the import section. This is not allowed:\n{}",
            comment.reg_match
        ));
    }

    // Error if the star import is used for non prelude- or traits-like imports.
    // TODO: This is commented for now because it requires several non-trival changes in the code.
    // let invalid_star_import = header.iter().any(|t| {
    //     t.token == UseStar
    //         && !t.reg_match.contains("prelude::*")
    //         && !t.reg_match.contains("traits::*")
    //         && !t.reg_match.contains("super::*")
    // });
    //
    // if invalid_star_import {
    //     Err("Star imports only allowed for `prelude`, `traits`, and `super`
    // modules.".to_string())?; }

    // Build a mapping between tokens and registered entries.
    let mut map = HashMap::<HeaderToken, Vec<String>>::new();
    for elem in header {
        map.entry(elem.token).or_default().push(elem.to_string());
    }

    // Remove standard linter configuration from the configuration found in the file.
    if is_main_file {
        let vec = map.entry(ModuleAttribAllow).or_default();
        vec.retain(|t| !STD_LINTER_ATTRIBS.iter().map(|s| t.contains(s)).any(|b| b));
        if vec.is_empty() {
            map.remove(&ModuleAttribAllow);
        }

        let vec = map.entry(ModuleAttribDeny).or_default();
        vec.retain(|t| !STD_LINTER_ATTRIBS.iter().map(|s| t.contains(s)).any(|b| b));
        if vec.is_empty() {
            map.remove(&ModuleAttribDeny);
        }

        let vec = map.entry(ModuleAttribWarn).or_default();
        vec.retain(|t| !STD_LINTER_ATTRIBS.iter().map(|s| t.contains(s)).any(|b| b));
        if vec.is_empty() {
            map.remove(&ModuleAttribWarn);
        }

        let std_linter_attribs = STD_LINTER_ATTRIBS.iter().map(|t| format!("#![{}]\n", t));
        map.entry(StandardLinterConfig).or_default().extend(std_linter_attribs);
    }

    // Print the results.
    let mut out = String::new();
    print_section(&mut out, &mut map, &[ModuleDoc]);
    print_section(&mut out, &mut map, &[ModuleComment]);
    print_section(&mut out, &mut map, &[ModuleAttrib]);
    print_h2(&mut out, &map, &[ModuleAttribAllowIncFeat, ModuleAttribFeature], "Features");
    print_section(&mut out, &mut map, &[ModuleAttribAllowIncFeat, ModuleAttribFeature]);
    if !STD_LINTER_ATTRIBS.is_empty() {
        print_h2(&mut out, &map, &[StandardLinterConfig], "Standard Linter Configuration");
        print_section(&mut out, &mut map, &[StandardLinterConfig]);
    }
    print_h2(
        &mut out,
        &map,
        &[ModuleAttribAllow, ModuleAttribDeny, ModuleAttribWarn],
        "Non-Standard Linter Configuration",
    );
    print_section(&mut out, &mut map, &[ModuleAttribAllow, ModuleAttribDeny, ModuleAttribWarn]);

    print_section(&mut out, &mut map, &[CrateUseStar, UseStar]);
    print_section(&mut out, &mut map, &[CrateUse]);
    print_section(&mut out, &mut map, &[Use]);

    print_h1(&mut out, &map, &[PubMod, CratePubUseStar, PubUseStar, CratePubUse, PubUse], "Export");
    print_section(&mut out, &mut map, &[PubMod]);
    print_section(&mut out, &mut map, &[CratePubUseStar, PubUseStar, CratePubUse, PubUse]);
    out.push_str("\n\n");
    out.push_str(&input[total_len..]);
    Ok(out)
}

fn main() {
    process_path(".", Action::Format);
}



// =============
// === Tests ===
// =============

#[test]
fn test_formatting() {
    let input = r#"//! Module-level documentation
//! written in two lines.

#![warn(missing_copy_implementations)]
#![allow(incomplete_features)]
#![recursion_limit = "512"]
pub use lib_f::item_1;
pub mod mod1;
use crate::prelude::*;
use crate::lib_b;
use lib_c;
pub use crate::lib_e;
use crate::lib_a;
use lib_d::item_1;
use logger::traits::*;
pub mod mod2;
pub struct Struct1 {}
"#;

    let output = r#"//! Module-level documentation
//! written in two lines.

#![recursion_limit = "512"]

// === Features ===
#![allow(incomplete_features)]

// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]

// === Non-Standard Linter Configuration ===
#![warn(missing_copy_implementations)]

use crate::prelude::*;
use logger::traits::*;

use crate::lib_b;
use crate::lib_a;

use lib_c;
use lib_d::item_1;


// ==============
// === Export ===
// ==============

pub mod mod1;
pub mod mod2;

pub use crate::lib_e;
pub use lib_f::item_1;



pub struct Struct1 {}
"#;
    assert_eq!(process_file_content(input.into(), true), Ok(output.into()));
}
