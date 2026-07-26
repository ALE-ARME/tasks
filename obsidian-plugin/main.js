const { Plugin, MarkdownView, Notice, ItemView } = require('obsidian');

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
        const container = this.containerEl.children[1];
        container.empty();
        container.classList.add("tasks-app-container");
        
        const activeFile = this.app.workspace.getActiveFile();
        if (activeFile && activeFile.extension === "md") {
            const content = await this.app.vault.read(activeFile);
            this.renderTasks(container, content, activeFile);
        } else {
            container.createEl("h3", { text: "Apri un file .md con i tuoi task per visualizzarli in stile Tasks.org" });
        }
    }

    renderTasks(container, mdText, file) {
        container.empty();

        const header = container.createDiv({ cls: "tasks-app-header" });
        const title = header.createDiv({ cls: "tasks-app-title" });
        title.innerHTML = `<span>📋 Tasks.org View</span>`;

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

                const section = listContainer.createDiv({ cls: "tasks-app-list-section" });
                section.createEl("h4", { cls: "tasks-app-list-title", text: `📁 ${listName}` });

                filteredTasks.forEach(task => renderTaskItem(section, task, file, this.app));
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
        if (trimmed.startsWith("## ")) {
            const headerTitle = trimmed.substring(3).trim();
            if (headerTitle.includes("Completati")) {
                currentList = "Completati";
            } else {
                currentList = headerTitle;
                if (!pending[currentList]) pending[currentList] = [];
            }
            return;
        }

        const taskMatch = line.match(/^(\s*)-\s*\[([ xX])\]\s*(.*)$/);
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

            const taskObj = {
                lineIndex: index,
                title: rawTitle,
                isCompleted,
                priority,
                dueDate,
                tags,
                notes: "",
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

    return { pending, completed };
}

function renderTaskItem(container, task, file, app) {
    const item = container.createDiv({ cls: `tasks-app-item ${task.isCompleted ? 'completed' : ''}` });
    const main = item.createDiv({ cls: "tasks-app-item-main" });

    const checkbox = main.createDiv({ cls: "tasks-app-checkbox" });
    checkbox.addEventListener("click", async () => {
        const content = await app.vault.read(file);
        const lines = content.split("\n");
        const line = lines[task.lineIndex];
        if (line) {
            if (task.isCompleted) {
                lines[task.lineIndex] = line.replace("- [x]", "- [ ]").replace("- [X]", "- [ ]");
            } else {
                lines[task.lineIndex] = line.replace("- [ ]", "- [x]");
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
        const dBadge = badges.createDiv({ cls: "tasks-app-badge date", text: `📅 ${task.dueDate}` });
    }
    task.tags.forEach(tg => {
        badges.createDiv({ cls: "tasks-app-badge tag", text: `#${tg}` });
    });
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
            const activeFile = this.app.workspace.getActiveFile();
            const mdText = source.trim() ? source : (activeFile ? await this.app.vault.read(activeFile) : "");
            
            const container = el.createDiv({ cls: "tasks-app-container" });
            const view = new TasksAppView(null);
            view.renderTasks(container, mdText, activeFile);
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
