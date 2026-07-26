const { Plugin, MarkdownView, Notice, ItemView, TFile } = require('obsidian');

const VIEW_TYPE_TASKS = "tasks-app-custom-view";

class TasksAppView extends ItemView {
    constructor(leaf) {
        super(leaf);
    }
    getViewType() {
        return VIEW_TYPE_TASKS;
    }
    getDisplayText() {
        return "Tasks App View";
    }
    getIcon() {
        return "check-square";
    }
    async onOpen() {
        this.refreshView();
        this.registerEvent(this.app.workspace.on('file-open', () => this.refreshView()));
        this.registerEvent(this.app.vault.on('modify', (file) => {
            const activeFile = this.app.workspace.getActiveFile();
            if (activeFile && file.path === activeFile.path) {
                this.refreshView();
            }
        }));
    }

    async refreshView() {
        const container = this.containerEl.children[1];
        if (!container) return;
        container.empty();
        container.classList.add("tasks-app-container");

        const activeFile = this.app.workspace.getActiveFile();
        if (activeFile && activeFile.extension === "md") {
            const content = await this.app.vault.read(activeFile);
            this.renderTasks(container, content, activeFile);
        } else {
            // Try to find tasks.md in root
            const tasksFile = this.app.vault.getAbstractFileByPath("tasks.md") || 
                              this.app.vault.getMarkdownFiles().find(f => f.name.toLowerCase().includes("task"));
            if (tasksFile && tasksFile instanceof TFile) {
                const content = await this.app.vault.read(tasksFile);
                this.renderTasks(container, content, tasksFile);
            } else {
                container.createEl("h3", { text: "Apri o seleziona un file .md per visualizzare i tuoi task" });
            }
        }
    }

    renderTasks(container, mdText, file) {
        container.empty();

        const header = container.createDiv({ cls: "tasks-app-header" });
        const title = header.createDiv({ cls: "tasks-app-title" });
        title.innerHTML = `<span>📋 Tasks.org View (${file ? file.name : ''})</span>`;

        const searchInput = container.createEl("input", {
            cls: "tasks-app-search-input",
            attr: { placeholder: "🔍 Cerca task, tag, note..." }
        });

        const filterBar = container.createDiv({ cls: "tasks-app-filter-bar" });
        
        const parsed = parseMarkdownTasks(mdText);
        const lists = Object.keys(parsed.pending);
        
        let currentFilter = "Tutti";

        const renderFiltered = (filterQuery = "", selectedList = currentFilter) => {
            let listContainer = container.querySelector(".tasks-app-lists-body");
            if (!listContainer) {
                listContainer = container.createDiv({ cls: "tasks-app-lists-body" });
            }
            listContainer.empty();

            let hasPending = false;

            // Render Pending Tasks by List
            for (const [listName, tasks] of Object.entries(parsed.pending)) {
                if (selectedList !== "Tutti" && selectedList !== "Completati" && selectedList !== listName) continue;
                if (selectedList === "Completati") continue;

                const filteredTasks = tasks.filter(t => 
                    t.title.toLowerCase().includes(filterQuery.toLowerCase()) ||
                    t.tags.some(tg => tg.toLowerCase().includes(filterQuery.toLowerCase())) ||
                    t.notes.toLowerCase().includes(filterQuery.toLowerCase())
                );

                if (filteredTasks.length === 0) continue;
                hasPending = true;

                const section = listContainer.createDiv({ cls: "tasks-app-list-section" });
                section.createEl("h4", { cls: "tasks-app-list-title", text: `📁 ${listName}` });

                filteredTasks.forEach(task => renderTaskItem(section, task, file, this.app));
            }

            if (!hasPending && selectedList !== "Completati") {
                const emptyDiv = listContainer.createDiv({ cls: "tasks-app-empty" });
                emptyDiv.createEl("p", { text: "Nessun task in sospeso trovato." });
            }

            // Render Completed Tasks
            if (selectedList === "Tutti" || selectedList === "Completati") {
                const filteredCompleted = parsed.completed.filter(t => 
                    t.title.toLowerCase().includes(filterQuery.toLowerCase())
                );

                if (filteredCompleted.length > 0) {
                    const section = listContainer.createDiv({ cls: "tasks-app-list-section" });
                    section.createEl("h4", { cls: "tasks-app-list-title", text: "☑️ Completati" });
                    filteredCompleted.forEach(task => renderTaskItem(section, task, file, this.app));
                }
            }
        };

        // Render Filter Chips
        const createChip = (name) => {
            const chip = filterBar.createDiv({ cls: `tasks-app-chip ${name === currentFilter ? 'active' : ''}`, text: name });
            chip.addEventListener("click", () => {
                filterBar.querySelectorAll(".tasks-app-chip").forEach(c => c.classList.remove("active"));
                chip.classList.add("active");
                currentFilter = name;
                renderFiltered(searchInput.value, currentFilter);
            });
        };

        createChip("Tutti");
        lists.forEach(l => createChip(l));
        createChip("Completati");

        searchInput.addEventListener("input", (e) => {
            renderFiltered(e.target.value, currentFilter);
        });

        renderFiltered();
    }
}

function parseMarkdownTasks(mdText) {
    const lines = mdText.split("\n");
    const pending = {};
    const completed = [];
    let currentList = "Inbox";

    lines.forEach((line, index) => {
        const trimmed = line.trim();
        if (trimmed.startsWith("#")) {
            const headerTitle = trimmed.replace(/^#+\s*/, "").trim();
            if (headerTitle.toLowerCase().includes("completati")) {
                currentList = "Completati";
            } else if (!headerTitle.toLowerCase().includes("tasks")) {
                currentList = headerTitle;
                if (!pending[currentList]) pending[currentList] = [];
            }
            return;
        }

        const taskMatch = line.match(/^(\s*)[-*+]\s*\[([ xX])\]\s*(.*)$/);
        if (taskMatch) {
            const indent = taskMatch[1].length;
            const isCompleted = taskMatch[2].toLowerCase() === "x";
            let rawTitle = taskMatch[3];

            const tags = (rawTitle.match(/#[\w-]+/g) || []).map(t => t.substring(1));
            rawTitle = rawTitle.replace(/#[\w-]+/g, "").trim();

            let priority = "none";
            if (rawTitle.includes("🔴")) priority = "high";
            else if (rawTitle.includes("🟡")) priority = "medium";
            else if (rawTitle.includes("🔵")) priority = "low";
            rawTitle = rawTitle.replace(/[🔴🟡🔵]/g, "").trim();

            const dueDateMatch = rawTitle.match(/📅\s*([\d-]{10}(?:\s*[\d:]{5})?)/);
            const dueDate = dueDateMatch ? dueDateMatch[1] : null;
            rawTitle = rawTitle.replace(/📅\s*[\d-]{10}(?:\s*[\d:]{5})?/, "").trim();

            // Extract notes (lines underneath with indent)
            let notes = "";
            let nextIndex = index + 1;
            while (nextIndex < lines.length) {
                const nextLine = lines[nextIndex];
                const nextTrimmed = nextLine.trim();
                if (nextTrimmed.startsWith("- [") || nextTrimmed.startsWith("#")) break;
                if (nextLine.startsWith("    ") || nextLine.startsWith("\t")) {
                    notes += (notes ? "\n" : "") + nextTrimmed;
                    nextIndex++;
                } else {
                    break;
                }
            }

            const taskObj = {
                lineIndex: index,
                title: rawTitle,
                isCompleted,
                priority,
                dueDate,
                tags,
                notes,
                indent,
                listName: currentList
            };

            if (isCompleted) {
                completed.push(taskObj);
            } else {
                if (!pending[currentList]) pending[currentList] = [];
                pending[currentList].push(taskObj);
            }
        }
    });

    if (Object.keys(pending).length === 0) {
        pending["Inbox"] = [];
    }

    return { pending, completed };
}

function renderTaskItem(container, task, file, app) {
    const item = container.createDiv({ cls: `tasks-app-item ${task.isCompleted ? 'completed' : ''}` });
    const main = item.createDiv({ cls: "tasks-app-item-main" });

    const checkbox = main.createDiv({ cls: "tasks-app-checkbox" });
    checkbox.addEventListener("click", async () => {
        if (!file) return;
        const content = await app.vault.read(file);
        const lines = content.split("\n");
        const line = lines[task.lineIndex];
        if (line) {
            if (task.isCompleted) {
                lines[task.lineIndex] = line.replace(/\[[xX]\]/, "[ ]");
            } else {
                lines[task.lineIndex] = line.replace(/\[\s*\]/, "[x]");
            }
            await app.vault.modify(file, lines.join("\n"));
            new Notice(task.isCompleted ? "Task riaperto" : "Task completato!");
        }
    });

    const text = main.createDiv({ cls: "tasks-app-text", text: task.title });

    const badges = item.createDiv({ cls: "tasks-app-badges" });
    if (task.priority !== "none") {
        const pBadge = badges.createDiv({ cls: `tasks-app-badge priority-${task.priority}` });
        pBadge.text = task.priority.toUpperCase();
    }
    if (task.dueDate) {
        badges.createDiv({ cls: "tasks-app-badge date", text: `📅 ${task.dueDate}` });
    }
    task.tags.forEach(tg => {
        badges.createDiv({ cls: "tasks-app-badge tag", text: `#${tg}` });
    });

    if (task.notes) {
        const notesDiv = item.createDiv({ cls: "tasks-app-notes", text: task.notes });
    }
}

module.exports = class TasksAppPlugin extends Plugin {
    async onload() {
        console.log("Loading Tasks App View Plugin");

        this.registerView(
            VIEW_TYPE_TASKS,
            (leaf) => new TasksAppView(leaf)
        );

        this.addRibbonIcon("check-square", "Apri Tasks App View", () => {
            this.activateView();
        });

        // Register Markdown Codeblock Processor for ```tasks-app
        this.registerMarkdownCodeblockProcessor("tasks-app", async (source, el, ctx) => {
            const container = el.createDiv({ cls: "tasks-app-container" });
            
            let mdText = source.trim();
            let file = null;

            if (ctx.sourcePath) {
                const abstractFile = this.app.vault.getAbstractFileByPath(ctx.sourcePath);
                if (abstractFile instanceof TFile) {
                    file = abstractFile;
                    if (!mdText) {
                        mdText = await this.app.vault.read(file);
                    }
                }
            }

            if (!mdText) {
                const activeFile = this.app.workspace.getActiveFile();
                if (activeFile && activeFile.extension === "md") {
                    file = activeFile;
                    mdText = await this.app.vault.read(activeFile);
                }
            }

            const view = new TasksAppView(null);
            view.renderTasks(container, mdText || "# Tasks\n_Nessun task trovato_", file);

            // Register auto refresh on file modify
            this.registerEvent(this.app.vault.on('modify', async (modifiedFile) => {
                if (file && modifiedFile.path === file.path) {
                    const newContent = await this.app.vault.read(file);
                    view.renderTasks(container, newContent, file);
                }
            }));
        });
    }

    async activateView() {
        const { workspace } = this.app;
        let leaf = workspace.getLeavesOfType(VIEW_TYPE_TASKS)[0];
        if (!leaf) {
            leaf = workspace.getRightLeaf(false);
            await leaf.setViewState({ type: VIEW_TYPE_TASKS, active: true });
        }
        workspace.revealLeaf(leaf);
    }
};
