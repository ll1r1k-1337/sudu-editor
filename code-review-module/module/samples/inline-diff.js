import { initControlPanel } from "./control-panel.js";
import * as editorApi from "../src/codereview.js";

const workers = await editorApi.newWorkerPool("../src/worker.js", 3);

console.log("workers.getNumThreads()=", workers.getNumThreads());

const inlineDiff = editorApi.newInlineDiff({
  containerId: "editor",
  workers,
  disableParser: true
});

const initialLeftText =
    "function formatTitle(value) {\n" +
    "  return value.trim().toLowerCase();\n" +
    "}\n" +
    "\n" +
    "function renderUser(user) {\n" +
    "  const title = formatTitle(user.title);\n" +
    "  return `${title}: ${user.name}`;\n" +
    "}\n" +
    "\n" +
    "export function renderUsers(users) {\n" +
    "  return users.map(renderUser).join('\\n');\n" +
    "}\n";

const initialRightText =
    "function formatTitle(value) {\n" +
    "  return value.trim().toUpperCase();\n" +
    "}\n" +
    "\n" +
    "function renderUser(user) {\n" +
    "  const title = formatTitle(user.role);\n" +
    "  const name = user.displayName ?? user.name;\n" +
    "  return `${title}: ${name}`;\n" +
    "}\n" +
    "\n" +
    "export function renderUsers(users) {\n" +
    "  return users.filter(Boolean).map(renderUser).join('\\n');\n" +
    "}\n";

const leftModel = editorApi.newTextModel(initialLeftText, null, { path: "left.js" });
const rightModel = editorApi.newTextModel(initialRightText, null, { path: "right.js" });

leftModel.setEditListener(() => console.log("left model changed"));
rightModel.setEditListener(() => console.log("right model changed"));

inlineDiff.setDiffSizeListener((numLines, lineHeight, cssLineHeight) => {
  console.log("inline diff size", { numLines, lineHeight, cssLineHeight });
});

inlineDiff.setModel(leftModel, rightModel);
inlineDiff.focus();

const controller = inlineDiff.getController();
const controlPanel = initControlPanel(document.getElementById("editor"));

let compactView = false;
let leftReadonly = false;
let rightReadonly = false;

const applyReadonly = () => {
  inlineDiff.setReadonly(leftReadonly, rightReadonly);
  inlineDiff.focus();
};

const controls = {
  Up: () => {
    controller.canNavigateUp() && controller.navigateUp();
    inlineDiff.focus();
  },
  Down: () => {
    controller.canNavigateDown() && controller.navigateDown();
    inlineDiff.focus();
  },
  Compact: () => {
    compactView = !compactView;
    controller.setCompactView(compactView);
    inlineDiff.focus();
  },
  "RO-L": () => {
    leftReadonly = !leftReadonly;
    applyReadonly();
  },
  "RO-R": () => {
    rightReadonly = !rightReadonly;
    applyReadonly();
  },
  Reset: () => window.location.reload()
};

Object.entries(controls).forEach(([label, handler]) =>
    controlPanel.add(label, handler));
