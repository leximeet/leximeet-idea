#!/usr/bin/env node
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync, spawn } = require("node:child_process");
const { createRequire } = require("node:module");
const { setTimeout: delay } = require("node:timers/promises");
const { startFocusMonitor } = require("./fixtures/macos-focus-monitor.cjs");

const pluginRoot = path.resolve(__dirname, "..");
const { env } = process;
const baseline = process.argv.includes("--baseline");
const desktopOnly = process.argv.includes("--desktop-before");
const uiOnly = process.argv.includes("--ui-only");
const fullDecompiler = process.argv.includes("--full-decompiler");
const location = env.LEXIMEET_IDEA_TEST_OUTPUT || os.tmpdir();

const fixtureJava = [
  "package resilience;",
  "public class Resilience {",
  "    // A wander can build resilience, especially after a subtle change.",
  '    public String wander() { return "subtle LEXIUNMATCHEDSENTINEL"; }',
  "}",
].join("\n");

function put(dir, name, value) {
  const file = path.join(dir, name);
  fs.mkdirSync(path.dirname(file), { mode: 0o700, recursive: true });
  fs.writeFileSync(file, value, { mode: 0o600 });
}

function fixture(root) {
  const project = path.join(root, "resilience-workspace");
  put(project, ".leximeet-ui-fixture", "sandbox\n");
  put(project, "src/resilience/Resilience.java", fixtureJava);
  put(
    project,
    "src/resilience/wander_notes.txt",
    "A wander can build resilience.\n",
  );
  put(
    project,
    ".idea/modules.xml",
    [
      '<project version="4"><component name="ProjectModuleManager"><modules>',
      '<module fileurl="file://$PROJECT_DIR$/resilience-workspace.iml" filepath="$PROJECT_DIR$/resilience-workspace.iml" />',
      "</modules></component></project>",
    ].join("\n"),
  );
  put(
    project,
    "resilience-workspace.iml",
    [
      '<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager">',
      '<content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" /></content>',
      '<orderEntry type="sourceFolder" forTests="false" />',
      '<orderEntry type="module-library"><library name="resilience-tool.jar"><CLASSES>',
      '<root url="jar://$MODULE_DIR$/archives/resilience-tool.jar!/" /></CLASSES><JAVADOC /><SOURCES /></library></orderEntry>',
      "</component></module>",
    ].join("\n"),
  );
  const archiveBuild = path.join(root, "archive-build");
  const classes = path.join(archiveBuild, "classes");
  const file = path.join(archiveBuild, "WanderResilience.java");
  put(
    archiveBuild,
    "WanderResilience.java",
    'package fixture; public class WanderResilience { public String wander() { return "subtle"; } }\n',
  );
  put(classes, "fixture/subtle.txt", "A subtle word inside a JAR.\n");
  fs.mkdirSync(path.join(project, "archives"));
  const jdk = env.JAVA_HOME ? path.join(env.JAVA_HOME, "bin") : null;
  execFileSync(jdk ? path.join(jdk, "javac") : "javac", [
    "-g:none",
    "--release",
    "21",
    "-d",
    classes,
    file,
  ]);
  execFileSync(jdk ? path.join(jdk, "jar") : "jar", [
    "cf",
    path.join(project, "archives", "resilience-tool.jar"),
    "-C",
    classes,
    ".",
  ]);
  return project;
}

function startIdea(root, profile) {
  const selected = path.join(pluginRoot, "build/leximeet/idea-home.txt");
  if (!fs.existsSync(selected))
    throw new Error(
      "请先运行 ./gradlew buildPlugin uiTestJar，生成测试 IDE 路径。",
    );
  const home = fs.readFileSync(selected, "utf8").trim();
  const productDir = [
    home,
    path.join(home, "Resources"),
    path.join(home, "Contents/Resources"),
  ].find((directory) =>
    fs.existsSync(path.join(directory, "product-info.json")),
  );
  if (!productDir)
    throw new Error("Gradle 选择的 IDEA 已不可用，请重新运行 uiTestJar。");
  const mac = process.platform === "darwin";
  const product = JSON.parse(
    fs.readFileSync(path.join(productDir, "product-info.json"), "utf8"),
  );
  const target = mac ? "macOS" : "Linux";
  const arch = process.arch === "arm64" ? "aarch64" : "amd64";
  const launch = product.launch.find(
    (item) => item.os === target && item.arch === arch,
  );
  if (!launch) throw new Error("IDEA 安装与本机系统或架构不符。");
  const configs = {};
  for (const name of ["config", "system", "plugins", "log"]) {
    const dir = path.join(root, "ide-" + name);
    configs[name] = dir;
    fs.mkdirSync(dir, { mode: 0o700 });
  }
  const harness = path.join(pluginRoot, "build/libs/leximeet-idea-ui-test.jar");
  const pluginId = "com.github.weilhuang.leximeetidea";
  if (baseline) {
    if (!env.LEXIMEET_IDEA_BASELINE_JAR)
      throw new Error("缺少修改前模板的 LEXIMEET_IDEA_BASELINE_JAR");
    put(
      configs.plugins,
      "baseline/lib/baseline.jar",
      fs.readFileSync(env.LEXIMEET_IDEA_BASELINE_JAR),
    );
  } else {
    const dist = path.join(pluginRoot, "build/distributions");
    const candidates = fs
      .readdirSync(dist)
      .filter((file) => /^leximeet-idea-[\d.]+\.zip$/.test(file));
    assert.equal(candidates.length, 1, "请只保留本轮待验收插件包");
    execFileSync("unzip", [
      "-q",
      path.join(dist, candidates[0]),
      "-d",
      configs.plugins,
    ]);
  }
  if (!uiOnly && !baseline && !fullDecompiler) {
    // 签名视图用 IDE 自带的 class 声明浏览，避免首次反编译提示激活应用。
    put(
      configs.config,
      "disabled_plugins.txt",
      "org.jetbrains.java.decompiler\n",
    );
  }
  put(configs.plugins, "ui-test/lib/ui-test.jar", fs.readFileSync(harness));
  put(
    configs.config,
    "options/updates.xml",
    [
      '<application><component name="UpdatesConfigurable">',
      '<option name="CHECK_NEEDED" value="false" />',
      '<option name="PLUGINS_CHECK_NEEDED" value="false" />',
      "</component></application>",
    ].join("\n"),
  );
  const options = [
    ...Object.entries(configs).map(
      ([key, value]) => "-Didea." + key + ".path=" + value,
    ),
    "-Didea.is.internal=true",
    "-Didea.trust.all.projects=true",
    "-Didea.is.integration.test=true",
    "-Dide.show.tips.on.startup.default.value=false",
    "-Djb.consents.confirmation.enabled=false",
    "-Dide.mac.disableNativeMenus=true",
    "-Dapple.awt.UIElement=true",
    // 启动画面早于测试插件加载，须从进程入口就禁用。
    "-Dnosplash=true",
    "-Duser.language=en",
    "-Duser.country=US",
    "-Didea.required.plugins.id=" + pluginId + "," + pluginId + ".uitest",
    "-Dleximeet.test=true",
    "-Dleximeet.desktop.profile=" + profile,
    "-Dleximeet.ui.output=" + root,
    "-Dleximeet.ui.baseline=" + baseline,
    "-Dleximeet.ui.only=" + uiOnly,
    "-Dleximeet.ui.fullDecompiler=" + fullDecompiler,
  ];
  const file = path.join(root, "idea.vmoptions");
  put(
    root,
    "idea.vmoptions",
    fs.readFileSync(
      path.resolve(productDir, launch.vmOptionsFilePath),
      "utf8",
    ) +
      "\n" +
      options.join("\n") +
      "\n",
  );
  const project = fixture(root);
  const log = fs.openSync(path.join(root, "idea-process.log"), "w", 0o600);
  try {
    return spawn(path.resolve(productDir, launch.launcherPath), ["nosplash", project], {
      cwd: root,
      env: { ...env, IDEA_VM_OPTIONS: file },
      stdio: ["ignore", log, log],
      detached: true,
    });
  } finally {
    fs.closeSync(log);
  }
}

async function startDesktop(profile, root, focusMonitor) {
  const isFirstRun = !fs.existsSync(path.join(profile, "profile.json"));
  const application = desktopOnly
    ? env.LEXIMEET_DESKTOP_BASELINE_ROOT
    : env.LEXIMEET_DESKTOP_ROOT;
  if (!application)
    throw new Error("请用 LEXIMEET_DESKTOP_ROOT 指定 Desktop 源码目录。");
  const fromDesktop = createRequire(
    path.join(env.LEXIMEET_DESKTOP_ROOT, "package.json"),
  );
  const executablePath =
    env.LEXIMEET_ELECTRON_EXECUTABLE || fromDesktop("electron");
  const { _electron } = fromDesktop("playwright");
  const desktopEnv = {
    ...env,
    LEXIMEET_PROFILE: "test",
    LEXIMEET_DATA_DIR: profile,
    LEXIMEET_TEST_SILENT: "1",
    LEXIMEET_SEED_DEMO: "0",
  };
  delete desktopEnv.ELECTRON_RUN_AS_NODE;
  const app = await _electron.launch({
    executablePath,
    args: [application, "--env=prod"],
    cwd: application,
    env: desktopEnv,
    timeout: 55000,
  });
  try {
    focusMonitor?.track(app.process().pid);
    const page = await app.firstWindow({ timeout: 40000 });
    await page.waitForFunction(() => !!window.leximeet?.connectionSettings);
    const skip = page.getByRole("button", { name: "跳过教学", exact: true });
    const navigation = page.locator('[data-guide="nav-settings"]');
    if (isFirstRun) await skip.click({ timeout: 50000 });
    await navigation.waitFor({ timeout: 40000 });
    await navigation.click();
    const nav = page.getByRole("navigation", { name: "设置分类" });
    await nav.getByRole("button", { name: "插件连接", exact: true }).click();
    const section = page.getByRole("region", { name: "插件连接设置" });
    await section
      .getByText("本机服务就绪", { exact: true })
      .waitFor({ timeout: 50000 });
    await verifyDesktopBackground(app, root);
    return { app, page, section };
  } catch (failure) {
    await app.close();
    throw failure;
  }
}

// 与浏览器插件联调相同，读取应用自身的后台保护与窗口状态。
async function verifyDesktopBackground(app, root) {
  const result = await app.evaluate(({ BrowserWindow }) => ({
    ...globalThis.__leximeetTestBackground?.snapshot(),
    windows: BrowserWindow.getAllWindows().map((window) => ({
      visible: window.isVisible(),
      focused: window.isFocused(),
      focusable: window.isFocusable(),
    })),
  }));
  fs.appendFileSync(
    path.join(root, "desktop-background.jsonl"),
    JSON.stringify(result) + "\n",
    { mode: 0o600 },
  );
  assert.ok(
    result.active &&
      result.audioMuted &&
      result.violations.length === 0 &&
      result.windows.length > 0 &&
      result.windows.every(
        (window) => !window.visible && !window.focused && !window.focusable,
      ),
    "Desktop 后台保护未生效",
  );
}

async function prepareDictionaryFixture(page) {
  // 在隔离资料里启用真实词典，并收藏一个样例以验收双标签和筛选。
  await page.evaluate(async () => {
    const read = await window.leximeet.desktopState();
    const revision = read.profile.revision;
    await window.leximeet.desktopCommand({
      action: "setGoal",
      goal: "dictionary",
      expectedRevision: revision,
    });
    await window.leximeet.desktopCommand({ action: "collect", word: "wander" });
  });
}

async function waitFile(root, name, idea, seconds = 90) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    if (fs.existsSync(path.join(root, "failure.txt")))
      throw new Error(fs.readFileSync(path.join(root, "failure.txt"), "utf8"));
    if (fs.existsSync(path.join(root, name))) return;
    if (idea.exitCode !== null || idea.signalCode !== null)
      throw new Error(
        `IDEA 提前退出（${idea.signalCode || idea.exitCode}），请查看进程日志。`,
      );
    await delay(180);
  }
  throw new Error("等待 IDEA 验收阶段超时：" + name);
}

async function closeIdea(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  // 仅关闭本脚本创建的进程组，避免用名称杀掉日常 IDEA。
  try {
    process.kill(-child.pid, "SIGTERM");
  } catch (failure) {
    if (failure.code !== "ESRCH") throw failure;
  }
  for (
    let attempt = 0;
    attempt < 25 && child.exitCode === null && child.signalCode === null;
    attempt++
  )
    await delay(200);
  if (child.exitCode === null && child.signalCode === null) {
    try {
      process.kill(-child.pid, "SIGKILL");
    } catch (failure) {
      if (failure.code !== "ESRCH") throw failure;
    }
  }
}

async function main() {
  if (!["linux", "darwin"].includes(process.platform))
    throw new Error("IDEA 只读连接当前提供 macOS/Linux UDS。");
  fs.mkdirSync(location, { recursive: true });
  const root = fs.mkdtempSync(path.join(location, "leximeet-idea-ui-"));
  const profile = path.join(root, "desktop-profile");
  console.log("验收输出 " + root);
  const focusMonitor =
    process.platform === "darwin"
      ? await startFocusMonitor({ root: location })
      : null;
  let idea;
  let desktop;
  try {
    if (desktopOnly) {
      desktop = await startDesktop(profile, root, focusMonitor);
      await desktop.page.screenshot({
        path: path.join(root, "before-desktop.png"),
        animations: "disabled",
        scale: "css",
      });
      return;
    }
    idea = startIdea(root, profile);
    if (idea.pid) focusMonitor?.track(idea.pid);
    idea.on("error", (failure) => put(root, "failure.txt", failure.message));
    if (baseline || uiOnly) {
      await waitFile(root, "passed", idea, 180);
      console.log(fs.readFileSync(path.join(root, "steps.txt"), "utf8").trim());
      return;
    }
    await waitFile(root, "desktop-missing", idea, 180);
    desktop = await startDesktop(profile, root, focusMonitor);
    await prepareDictionaryFixture(desktop.page);
    await waitFile(root, "plugin-cancelled", idea);
    const discovered = desktop.section
      .locator(".connection-device")
      .filter({ hasText: "词遇 for IntelliJ IDEA" });
    await discovered
      .getByRole("button", { name: "一键连接", exact: true })
      .click({ timeout: 30000 });
    await discovered.getByText("等待插件确认", { exact: true }).waitFor();
    put(root, "desktop-invitation-visible", "ok\n");
    await waitFile(root, "idea-connected", idea);
    const active = desktop.section
      .locator(".connection-device")
      .filter({ hasText: "词遇 for IntelliJ IDEA" });
    await active
      .getByText("连接成功", { exact: true })
      .waitFor({ timeout: 20000 });
    await desktop.page.screenshot({
      path: path.join(root, "after-desktop.png"),
      animations: "disabled",
      scale: "css",
    });
    const browserWindow = await desktop.app.browserWindow(desktop.page);
    const desktopSize = await browserWindow.evaluate((window) =>
      window.getSize(),
    );
    await browserWindow.evaluate((window) => window.setSize(960, 680));
    await desktop.page.waitForFunction(() => innerWidth <= 980);
    await active
      .getByRole("button", { name: "断开连接", exact: true })
      .waitFor();
    await desktop.page.screenshot({
      path: path.join(root, "after-desktop-compact.png"),
      animations: "disabled",
      scale: "css",
    });
    await browserWindow.evaluate(
      (window, size) => window.setSize(...size),
      desktopSize,
    );
    await waitFile(root, "desktop-stop-requested", idea, 150);
    await verifyDesktopBackground(desktop.app, root);
    await desktop.app.close();
    desktop = undefined;
    await waitFile(root, "idea-lost", idea);
    desktop = await startDesktop(profile, root, focusMonitor);
    await waitFile(root, "idea-resumed", idea);
    await desktop.section
      .locator(".connection-device")
      .filter({ hasText: "词遇 for IntelliJ IDEA" })
      .getByRole("button", { name: "断开连接", exact: true })
      .click({ timeout: 30000 });
    await waitFile(root, "passed", idea);
    await desktop.section
      .getByText("还没有插件连接这台电脑", { exact: true })
      .waitFor();
    await verifyDesktopBackground(desktop.app, root);
    console.log(fs.readFileSync(path.join(root, "steps.txt"), "utf8").trim());
    console.log("PASS 桌面设置显示等待与已连接状态，窄窗可用，双方均可断开");
  } finally {
    const stopped = await Promise.allSettled([
      desktop?.app.close(),
      closeIdea(idea),
    ]);
    if (focusMonitor) {
      const evidence = await focusMonitor.stop();
      put(
        root,
        "background-focus.json",
        JSON.stringify(evidence, null, 2) + "\n",
      );
      assert.ok(
        evidence.complete && evidence.violations.length === 0,
        "本轮应用触发了前台切换，或焦点监测未完成",
      );
      if (fs.existsSync(path.join(root, "passed")) || (desktopOnly && desktop))
        console.log("PASS macOS 全程无测试应用抢占前台焦点");
    }
    const failed = stopped.find((result) => result.status === "rejected");
    if (failed) throw failed.reason;
  }
}

main().catch((failure) => {
  console.error(failure.message);
  process.exitCode = 1;
});
