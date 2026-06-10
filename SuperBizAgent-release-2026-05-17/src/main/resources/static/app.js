// ============================================================
// SuperBizAgent - 智能OnCall助手 前端应用
// Features: Chat, AIOps, Alert Handbook, Dark Mode, Export
// ============================================================

class SuperBizAgentApp {
    constructor() {
        this.apiBaseUrl = 'http://localhost:9900/api';
        this.currentMode = 'quick';
        this.sessionId = this.generateSessionId();
        this.isStreaming = false;
        this.currentChatHistory = [];
        this.chatHistories = this.loadChatHistories();
        this.isCurrentChatFromHistory = false;
        this.isDarkMode = this.loadThemePreference();
        this.alertHandbookData = [];
        this.handbookFilter = 'all';

        this.initializeElements();
        this.bindEvents();
        this.applyTheme();
        this.updateUI();
        this.initMarkdown();
        this.renderChatHistory();
        this.checkHealth();
        this.loadAlertHandbook();
    }

    // ==================== Initialization ====================

    initializeElements() {
        // Sidebar
        this.sidebar = document.querySelector('.sidebar');
        this.newChatBtn = document.getElementById('newChatBtn');
        this.handbookSidebarBtn = document.getElementById('handbookSidebarBtn');
        this.searchDocsSidebarBtn = document.getElementById('searchDocsSidebarBtn');
        this.exportChatBtn = document.getElementById('exportChatBtn');
        this.clearChatSidebarBtn = document.getElementById('clearChatSidebarBtn');
        this.themeToggleBtn = document.getElementById('themeToggleBtn');
        this.themeLabel = document.getElementById('themeLabel');
        this.themeIconLight = document.getElementById('themeIconLight');

        // Top bar
        this.aiOpsBtn = document.getElementById('aiOpsBtn');

        // Input
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.toolsBtn = document.getElementById('toolsBtn');
        this.toolsMenu = document.getElementById('toolsMenu');
        this.uploadFileItem = document.getElementById('uploadFileItem');
        this.searchDocsPopoverItem = document.getElementById('searchDocsPopoverItem');
        this.fileInput = document.getElementById('fileInput');

        // Chat
        this.chatMessages = document.getElementById('chatMessages');
        this.chatContainer = document.getElementById('chatContainer');
        this.welcomeGreeting = document.getElementById('welcomeGreeting');
        this.loadingOverlay = document.getElementById('loadingOverlay');
        this.chatHistoryList = document.getElementById('chatHistoryList');

        // Search popover
        this.searchOverlay = document.getElementById('searchOverlay');
        this.searchPopover = document.getElementById('searchPopover');
        this.searchPopoverClose = document.getElementById('searchPopoverClose');
        this.searchPopoverInput = document.getElementById('searchPopoverInput');
        this.searchPopoverResults = document.getElementById('searchPopoverResults');

        // Handbook
        this.handbookOverlay = document.getElementById('handbookOverlay');
        this.handbookPanel = document.getElementById('handbookPanel');
        this.handbookCloseBtn = document.getElementById('handbookCloseBtn');
        this.handbookSearchInput = document.getElementById('handbookSearchInput');
        this.handbookStats = document.getElementById('handbookStats');
        this.handbookList = document.getElementById('handbookList');

        // Toast
        this.toastContainer = document.getElementById('toastContainer');

        // Quick suggestions
        this.quickSuggestions = document.querySelectorAll('.quick-suggestion-chip');
    }

    bindEvents() {
        // New chat
        this.newChatBtn?.addEventListener('click', () => this.newChat());

        // Clear chat (sidebar)
        this.clearChatSidebarBtn?.addEventListener('click', () => this.confirmClearChat());

        // Export
        this.exportChatBtn?.addEventListener('click', () => this.exportConversation());

        // Theme toggle
        this.themeToggleBtn?.addEventListener('click', () => this.toggleTheme());

        // AI Ops
        this.aiOpsBtn?.addEventListener('click', () => this.triggerAIOps());

        // Handbook
        this.handbookSidebarBtn?.addEventListener('click', () => this.openHandbook());
        this.handbookCloseBtn?.addEventListener('click', () => this.closeHandbook());
        this.handbookOverlay?.addEventListener('click', () => this.closeHandbook());
        this.handbookSearchInput?.addEventListener('input', () => this.filterHandbook());

        // Handbook filter chips
        this.handbookStats?.addEventListener('click', (e) => {
            const chip = e.target.closest('.handbook-stat-chip');
            if (chip) { this.handbookFilter = chip.dataset.filter; this.updateHandbookFilters(); this.renderHandbook(); }
        });

        // Search popover
        this.searchDocsSidebarBtn?.addEventListener('click', () => this.openSearchPopover());
        this.searchDocsPopoverItem?.addEventListener('click', () => { this.closeToolsMenu(); this.openSearchPopover(); });
        this.searchPopoverClose?.addEventListener('click', () => this.closeSearchPopover());
        this.searchOverlay?.addEventListener('click', (e) => { if (e.target === this.searchOverlay) this.closeSearchPopover(); });
        this.searchPopoverInput?.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.executeSearch();
        });
        let searchDebounce = null;
        this.searchPopoverInput?.addEventListener('input', () => {
            clearTimeout(searchDebounce);
            const val = this.searchPopoverInput.value.trim();
            if (val.length >= 2) { searchDebounce = setTimeout(() => this.executeSearch(), 400); }
            else if (val.length === 0) { this.searchPopoverResults.innerHTML = '<div class="search-popover-empty">输入关键词开始搜索知识库</div>'; }
        });

        // Click outside closes
        document.addEventListener('click', (e) => {
            if (this.toolsBtn && this.toolsMenu && !this.toolsBtn.contains(e.target) && !this.toolsMenu.contains(e.target)) {
                this.closeToolsMenu();
            }
        });

        // Send message
        this.sendButton?.addEventListener('click', () => this.sendMessage());
        this.messageInput?.addEventListener('keypress', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.sendMessage(); }
        });

        // Tools
        this.toolsBtn?.addEventListener('click', (e) => { e.stopPropagation(); this.toggleToolsMenu(); });
        this.uploadFileItem?.addEventListener('click', () => { this.fileInput?.click(); this.closeToolsMenu(); });
        this.fileInput?.addEventListener('change', (e) => this.handleFileSelect(e));

        // Quick suggestions
        this.quickSuggestions?.forEach(chip => {
            chip.addEventListener('click', () => {
                const prompt = chip.dataset.prompt;
                if (prompt) { this.messageInput.value = prompt; this.sendMessage(); }
            });
        });

        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') { e.preventDefault(); this.openHandbook(); }
            if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); this.openSearchPopover(); }
        });
    }

    // ==================== Theme ====================

    loadThemePreference() {
        try {
            return localStorage.getItem('sb-theme') === 'dark';
        } catch { return false; }
    }

    saveThemePreference() {
        try {
            localStorage.setItem('sb-theme', this.isDarkMode ? 'dark' : 'light');
        } catch {}
    }

    applyTheme() {
        document.documentElement.setAttribute('data-theme', this.isDarkMode ? 'dark' : 'light');
        if (this.themeLabel) this.themeLabel.textContent = this.isDarkMode ? '亮色模式' : '暗色模式';
    }

    toggleTheme() {
        this.isDarkMode = !this.isDarkMode;
        this.applyTheme();
        this.saveThemePreference();
        this.showToast(this.isDarkMode ? '已切换到暗色模式 🌙' : '已切换到亮色模式 ☀️', 'info');
    }

    // ==================== Health Check ====================

    async checkHealth() {
        try {
            const resp = await fetch(`${this.apiBaseUrl}/health`);
            if (resp.ok) {
                const data = await resp.json();
                console.log('[Health]', data.status, '| sessions:', data.activeSessions);
            }
        } catch (e) {
            console.warn('[Health] Backend not reachable:', e.message);
        }
    }

    // ==================== Alert Handbook ====================

    async loadAlertHandbook() {
        try {
            const resp = await fetch(`${this.apiBaseUrl}/alert-handbook/all`);
            const data = await resp.json();
            if (data.code === 200) {
                this.alertHandbookData = data.data || [];
                this.renderHandbook();
                console.log(`[Handbook] Loaded ${this.alertHandbookData.length} alert rules`);
            }
        } catch (e) {
            console.error('[Handbook] Load failed:', e);
            if (this.handbookList) {
                this.handbookList.innerHTML = '<div style="text-align:center;padding:40px;color:var(--error);">⚠️ 加载告警手册失败，请确保后端服务已启动</div>';
            }
        }
    }

    getFilteredHandbookData() {
        let data = [...this.alertHandbookData];

        // Severity filter
        if (this.handbookFilter !== 'all') {
            data = data.filter(a => a.severity === this.handbookFilter);
        }

        // Search filter
        const keyword = this.handbookSearchInput?.value.trim().toLowerCase() || '';
        if (keyword) {
            data = data.filter(a =>
                a.name.toLowerCase().includes(keyword) ||
                a.description.toLowerCase().includes(keyword) ||
                a.category.toLowerCase().includes(keyword) ||
                (a.id && a.id.toLowerCase().includes(keyword))
            );
        }

        return data;
    }

    renderHandbook() {
        if (!this.handbookList) return;

        const data = this.getFilteredHandbookData();

        if (data.length === 0) {
            this.handbookList.innerHTML = `
                <div style="text-align:center;padding:48px 20px;color:var(--text-tertiary);">
                    <div style="font-size:48px;margin-bottom:12px;">🔍</div>
                    <div style="font-size:14px;">未找到匹配的告警规则</div>
                </div>`;
            return;
        }

        this.handbookList.innerHTML = data.map(alert => `
            <div class="handbook-alert-card" data-alert-id="${this.escapeHtml(alert.id)}">
                <div class="alert-card-header">
                    <span class="alert-card-id">${this.escapeHtml(alert.id)}</span>
                    <span class="alert-severity-badge ${alert.severity}">${this.getSeverityLabel(alert.severity)}</span>
                </div>
                <div class="alert-card-name">${this.escapeHtml(alert.name)}</div>
                <div class="alert-card-category">📂 ${this.escapeHtml(alert.category)}</div>
                <div class="alert-card-desc">${this.escapeHtml(alert.description)}</div>
                <div class="alert-card-details">
                    ${alert.possibleCauses && alert.possibleCauses.length > 0 ? `
                    <div class="alert-detail-section">
                        <h4>🔍 可能原因</h4>
                        <ul>${alert.possibleCauses.map(c => `<li>${this.escapeHtml(c)}</li>`).join('')}</ul>
                    </div>` : ''}
                    ${alert.diagnosticSteps && alert.diagnosticSteps.length > 0 ? `
                    <div class="alert-detail-section">
                        <h4>🛠️ 排查步骤</h4>
                        <ul>${alert.diagnosticSteps.map(s => `<li>${this.escapeHtml(s)}</li>`).join('')}</ul>
                    </div>` : ''}
                    ${alert.resolutionSteps && alert.resolutionSteps.length > 0 ? `
                    <div class="alert-detail-section">
                        <h4>✅ 处理方案</h4>
                        <ul>${alert.resolutionSteps.map(s => `<li>${this.escapeHtml(s)}</li>`).join('')}</ul>
                    </div>` : ''}
                    ${alert.escalationRule ? `
                    <div class="escalation-rule">⬆️ ${this.escapeHtml(alert.escalationRule)}</div>` : ''}
                </div>
            </div>
        `).join('');

        // Card click to expand
        this.handbookList.querySelectorAll('.handbook-alert-card').forEach(card => {
            card.addEventListener('click', () => card.classList.toggle('expanded'));
        });
    }

    filterHandbook() {
        this.renderHandbook();
    }

    updateHandbookFilters() {
        this.handbookStats?.querySelectorAll('.handbook-stat-chip').forEach(chip => {
            chip.classList.toggle('active', chip.dataset.filter === this.handbookFilter);
        });
    }

    getSeverityLabel(severity) {
        const map = { critical: '严重', warning: '警告', info: '提示' };
        return map[severity] || severity;
    }

    openHandbook() { this.toggleHandbook(true); }
    closeHandbook() { this.toggleHandbook(false); }

    toggleHandbook(show) {
        if (show === undefined) show = !this.handbookPanel?.classList.contains('active');
        this.handbookPanel?.classList.toggle('active', show);
        this.handbookOverlay?.classList.toggle('active', show);
        if (show) {
            this.renderHandbook();
            this.handbookSearchInput?.focus();
        }
    }

    // ==================== Chat Management ====================

    newChat() {
        if (this.isStreaming) {
            this.showToast('请等待当前操作完成', 'warning');
            return;
        }

        if (this.currentChatHistory.length > 0) {
            if (this.isCurrentChatFromHistory) {
                this.updateCurrentChatHistory();
            } else {
                this.saveCurrentChat();
            }
        }

        this.isStreaming = false;
        this.messageInput ? this.messageInput.value = '' : null;
        this.currentChatHistory = [];
        this.isCurrentChatFromHistory = false;
        this.chatMessages ? this.chatMessages.innerHTML = '' : null;
        this.sessionId = this.generateSessionId();
        this.currentMode = 'quick';
        this.updateUI();
        this.setChatCentered(true);
        this.renderChatHistory();
        this.showToast('已创建新对话 ✨', 'success');
    }

    confirmClearChat() {
        if (this.currentChatHistory.length === 0) {
            this.showToast('当前对话为空', 'info');
            return;
        }
        // Simple clear without confirmation dialog to keep UX light
        if (this.chatMessages) this.chatMessages.innerHTML = '';
        this.currentChatHistory = [];
        this.isCurrentChatFromHistory = false;
        this.sessionId = this.generateSessionId();
        this.setChatCentered(true);
        this.renderChatHistory();
        this.showToast('对话已清空 🗑️', 'success');
    }

    setChatCentered(centered) {
        if (centered) {
            this.chatContainer?.classList.add('centered');
            this.welcomeGreeting && (this.welcomeGreeting.style.display = '');
        } else {
            this.chatContainer?.classList.remove('centered');
            this.welcomeGreeting && (this.welcomeGreeting.style.display = 'none');
        }
    }

    saveCurrentChat() {
        if (this.currentChatHistory.length === 0) return;
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex !== -1) { this.updateCurrentChatHistory(); return; }

        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        const title = firstUserMessage
            ? (firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : ''))
            : '新对话';

        this.chatHistories.unshift({
            id: this.sessionId,
            title: title,
            messages: [...this.currentChatHistory],
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        });

        if (this.chatHistories.length > 50) this.chatHistories = this.chatHistories.slice(0, 50);
        this.saveChatHistories();
    }

    updateCurrentChatHistory() {
        if (this.currentChatHistory.length === 0) return;
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex === -1) { this.saveCurrentChat(); return; }

        const history = this.chatHistories[existingIndex];
        history.messages = [...this.currentChatHistory];
        history.updatedAt = new Date().toISOString();

        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        if (firstUserMessage) {
            const newTitle = firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : '');
            if (history.title !== newTitle) history.title = newTitle;
        }
        this.saveChatHistories();
    }

    loadChatHistories() {
        try { return JSON.parse(localStorage.getItem('sb-chatHistories') || '[]'); }
        catch { return []; }
    }

    saveChatHistories() {
        try { localStorage.setItem('sb-chatHistories', JSON.stringify(this.chatHistories)); }
        catch { console.error('Failed to save chat histories'); }
    }

    renderChatHistory() {
        if (!this.chatHistoryList) return;
        this.chatHistoryList.innerHTML = '';

        if (this.chatHistories.length === 0) return;

        this.chatHistories.forEach(history => {
            const item = document.createElement('div');
            item.className = 'history-item';
            item.dataset.historyId = history.id;

            const date = new Date(history.updatedAt);
            const timeStr = this.formatRelativeTime(date);

            item.innerHTML = `
                <div class="history-item-content">
                    <div class="history-item-title">${this.escapeHtml(history.title)}</div>
                    <div class="history-item-time">${timeStr}</div>
                </div>
                <button class="history-item-delete" data-history-id="${history.id}" title="删除">
                    <svg viewBox="0 0 24 24" fill="none"><path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
                </button>
            `;

            item.addEventListener('click', (e) => {
                if (!e.target.closest('.history-item-delete')) {
                    this.loadChatHistory(history.id);
                }
            });

            item.querySelector('.history-item-delete')?.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteChatHistory(history.id);
            });

            this.chatHistoryList.appendChild(item);
        });
    }

    loadChatHistory(historyId) {
        const history = this.chatHistories.find(h => h.id === historyId);
        if (!history) return;

        if (this.currentChatHistory.length > 0 && this.sessionId !== historyId) {
            if (this.isCurrentChatFromHistory) this.updateCurrentChatHistory();
            else this.saveCurrentChat();
        }

        this.sessionId = history.id;
        this.currentChatHistory = [...history.messages];
        this.isCurrentChatFromHistory = true;

        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
            history.messages.forEach(msg => {
                this.addMessage(msg.type, msg.content, false, false);
            });
        }

        this.setChatCentered(this.currentChatHistory.length === 0);
        this.renderChatHistory();
    }

    deleteChatHistory(historyId) {
        this.chatHistories = this.chatHistories.filter(h => h.id !== historyId);
        this.saveChatHistories();
        this.renderChatHistory();

        if (this.sessionId === historyId) {
            this.currentChatHistory = [];
            if (this.chatMessages) this.chatMessages.innerHTML = '';
            this.sessionId = this.generateSessionId();
            this.setChatCentered(true);
        }
        this.showToast('对话已删除', 'info');
    }

    async exportConversation() {
        try {
            const resp = await fetch(`${this.apiBaseUrl}/chat/export/${this.sessionId}`);
            const data = await resp.json();

            if (data.code === 200 && data.data?.markdown) {
                const blob = new Blob([data.data.markdown], { type: 'text/markdown' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `conversation-${this.sessionId}.md`;
                a.click();
                URL.revokeObjectURL(url);
                this.showToast('对话已导出 📥', 'success');
            } else {
                // Export locally from current chat history
                this.exportLocalConversation();
            }
        } catch (e) {
            this.exportLocalConversation();
        }
    }

    exportLocalConversation() {
        let markdown = '# SuperBizAgent 对话记录\n\n';
        markdown += `> 导出时间: ${new Date().toLocaleString()}\n`;
        markdown += `> 会话ID: ${this.sessionId}\n\n---\n\n`;

        this.currentChatHistory.forEach(msg => {
            if (msg.type === 'user') markdown += `### 👤 用户\n\n${msg.content}\n\n`;
            else markdown += `### 🤖 助手\n\n${msg.content}\n\n---\n\n`;
        });

        const blob = new Blob([markdown], { type: 'text/markdown' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `conversation-${this.sessionId}.md`;
        a.click();
        URL.revokeObjectURL(url);
        this.showToast('对话已导出(本地) 📥', 'success');
    }

    // ==================== Message Sending ====================

    async sendMessage() {
        let message = this.messageInput?.value.trim() || '';
        if (!message) return;
        if (this.isStreaming) { this.showToast('请等待当前操作完成', 'warning'); return; }

        this.addMessage('user', message);
        if (this.messageInput) this.messageInput.value = '';

        this.isStreaming = true;
        this.updateUI();

        try {
            if (this.currentMode === 'quick') {
                await this.sendQuickMessage(message);
            } else {
                await this.sendStreamMessage(message);
            }
        } catch (error) {
            console.error('Send failed:', error);
            this.addMessage('assistant', '抱歉，发送消息时出现错误：' + error.message);
        } finally {
            this.isStreaming = false;
            this.updateUI();
            if (this.isCurrentChatFromHistory && this.currentChatHistory.length > 0) {
                this.updateCurrentChatHistory();
                this.renderChatHistory();
            }
        }
    }

    async sendQuickMessage(message) {
        const loadingMsg = this.addLoadingMessage('思考中...');

        try {
            const resp = await fetch(`${this.apiBaseUrl}/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ Id: this.sessionId, Question: message })
            });

            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            const data = await resp.json();

            // Remove loading
            loadingMsg?.parentNode && loadingMsg.parentNode.removeChild(loadingMsg);

            if (data.code === 200 || data.message === 'success') {
                const chatResp = data.data;
                if (chatResp?.success) {
                    this.addMessage('assistant', chatResp.answer || '(空回复)');
                } else if (chatResp?.errorMessage) {
                    throw new Error(chatResp.errorMessage);
                } else {
                    this.addMessage('assistant', chatResp?.answer || '服务返回了空内容');
                }
            } else {
                throw new Error(data.message || '请求失败');
            }
        } catch (error) {
            loadingMsg?.parentNode && loadingMsg.parentNode.removeChild(loadingMsg);
            throw error;
        }
    }

    async sendStreamMessage(message) {
        try {
            const resp = await fetch(`${this.apiBaseUrl}/chat_stream`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ Id: this.sessionId, Question: message })
            });

            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

            const assistantMsgEl = this.addMessage('assistant', '', true);
            let fullResponse = '';
            const reader = resp.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) { this.handleStreamComplete(assistantMsgEl, fullResponse); break; }

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        if (!line.trim() || line.startsWith('id:') || line.startsWith('event:')) continue;

                        if (line.startsWith('data:')) {
                            const rawData = line.substring(5).trim();
                            if (rawData === '[DONE]') {
                                this.handleStreamComplete(assistantMsgEl, fullResponse);
                                return;
                            }
                            try {
                                const msg = JSON.parse(rawData);
                                if (msg?.type === 'content') {
                                    fullResponse += msg.data || '';
                                    this.updateStreamContent(assistantMsgEl, fullResponse);
                                } else if (msg?.type === 'done') {
                                    this.handleStreamComplete(assistantMsgEl, fullResponse);
                                    return;
                                } else if (msg?.type === 'error') {
                                    this.updateStreamContent(assistantMsgEl, '错误: ' + (msg.data || '未知'));
                                    return;
                                }
                            } catch {
                                fullResponse += rawData;
                                this.updateStreamContent(assistantMsgEl, fullResponse);
                            }
                        }
                    }
                }
            } finally { reader.releaseLock(); }
        } catch (error) { throw error; }
    }

    // ==================== Message Rendering ====================

    addMessage(type, content, isStreaming = false, saveToHistory = true) {
        const isFirstMessage = this.chatMessages?.querySelectorAll('.message').length === 0;

        if (!isStreaming && saveToHistory && content) {
            this.currentChatHistory.push({
                type, content,
                timestamp: new Date().toISOString()
            });
        }

        const msgDiv = document.createElement('div');
        msgDiv.className = `message ${type}${isStreaming ? ' streaming' : ''}`;

        // Avatar
        if (type === 'assistant') {
            const avatar = document.createElement('div');
            avatar.className = 'message-avatar';
            avatar.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/></svg>`;
            msgDiv.appendChild(avatar);
        } else {
            const avatar = document.createElement('div');
            avatar.className = 'message-avatar';
            avatar.style.background = 'linear-gradient(135deg, #64748b, #475569)';
            avatar.style.color = 'white';
            avatar.style.fontSize = '12px';
            avatar.style.fontWeight = '700';
            avatar.style.display = 'flex';
            avatar.style.alignItems = 'center';
            avatar.style.justifyContent = 'center';
            avatar.textContent = 'U';
            msgDiv.appendChild(avatar);
        }

        // Content wrapper
        const wrapper = document.createElement('div');
        wrapper.className = 'message-content-wrapper';

        const msgContent = document.createElement('div');
        msgContent.className = 'message-content';

        if (type === 'assistant' && !isStreaming) {
            msgContent.innerHTML = this.renderMarkdown(content);
            this.highlightCodeBlocks(msgContent);
        } else {
            msgContent.textContent = content;
        }

        wrapper.appendChild(msgContent);

        // Timestamp
        const timeDiv = document.createElement('div');
        timeDiv.className = 'message-time';
        timeDiv.textContent = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        wrapper.appendChild(timeDiv);

        // Actions (copy, regenerate) for assistant messages
        if (type === 'assistant' && !isStreaming) {
            const actions = document.createElement('div');
            actions.className = 'message-actions';
            actions.innerHTML = `
                <button class="message-action-btn copy-btn" title="复制">
                    <svg viewBox="0 0 24 24" fill="none"><rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/></svg>
                </button>
                <button class="message-action-btn regenerate-btn" title="重新生成">
                    <svg viewBox="0 0 24 24" fill="none"><polyline points="1 4 1 10 7 10" stroke="currentColor" stroke-width="2"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" stroke="currentColor" stroke-width="2"/></svg>
                </button>
            `;

            // Copy handler
            actions.querySelector('.copy-btn').addEventListener('click', async () => {
                await navigator.clipboard.writeText(content);
                const btn = actions.querySelector('.copy-btn');
                btn.classList.add('copied');
                btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none"><polyline points="20 6 9 17 4 12" stroke="currentColor" stroke-width="2"/></svg>';
                this.showToast('已复制到剪贴板 📋', 'success');
                setTimeout(() => {
                    btn.classList.remove('copied');
                    btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none"><rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/></svg>';
                }, 2000);
            });

            // Regenerate handler
            actions.querySelector('.regenerate-btn').addEventListener('click', () => {
                // Find the last user message to regenerate
                const lastUserMsg = [...this.currentChatHistory].reverse().find(m => m.type === 'user');
                if (lastUserMsg) {
                    // Remove the last assistant message from history
                    const lastAssistantIdx = [...this.currentChatHistory].reverse().findIndex(m => m.type === 'assistant');
                    if (lastAssistantIdx !== -1) {
                        this.currentChatHistory.splice(this.currentChatHistory.length - 1 - lastAssistantIdx, 1);
                    }
                    // Remove the message from DOM
                    msgDiv.remove();
                    // Re-send
                    this.messageInput.value = lastUserMsg.content;
                    this.sendMessage();
                }
            });

            wrapper.appendChild(actions);
        }

        msgDiv.appendChild(wrapper);
        this.chatMessages?.appendChild(msgDiv);

        if (isFirstMessage) this.setChatCentered(false);
        this.scrollToBottom();

        return msgDiv;
    }

    addLoadingMessage(content) {
        const msgDiv = document.createElement('div');
        msgDiv.className = 'message assistant';

        const avatar = document.createElement('div');
        avatar.className = 'message-avatar';
        avatar.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/></svg>`;
        msgDiv.appendChild(avatar);

        const wrapper = document.createElement('div');
        wrapper.className = 'message-content-wrapper';

        const msgContent = document.createElement('div');
        msgContent.className = 'message-content loading-message-content';

        const textSpan = document.createElement('span');
        textSpan.textContent = content;

        const spinner = document.createElement('span');
        spinner.className = 'loading-spinner-icon';
        spinner.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" fill="currentColor" opacity="0.2"/><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10c1.54 0 3-.36 4.28-1l-1.5-2.6C13.64 19.62 12.84 20 12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8c.84 0 1.64.38 2.18 1l1.5-2.6C13 2.36 12.54 2 12 2z" fill="currentColor"/></svg>`;

        msgContent.appendChild(textSpan);
        msgContent.appendChild(spinner);
        wrapper.appendChild(msgContent);
        msgDiv.appendChild(wrapper);

        this.chatMessages?.appendChild(msgDiv);

        const isFirstMessage = this.chatMessages?.querySelectorAll('.message').length === 1;
        if (isFirstMessage) this.setChatCentered(false);
        this.scrollToBottom();

        return msgDiv;
    }

    updateStreamContent(msgEl, content) {
        if (!msgEl) return;
        const msgContent = msgEl.querySelector('.message-content');
        if (msgContent) {
            msgContent.innerHTML = this.renderMarkdown(content);
            this.highlightCodeBlocks(msgContent);
            this.scrollToBottom();
        }
    }

    handleStreamComplete(msgEl, fullResponse) {
        if (msgEl) {
            msgEl.classList.remove('streaming');
            const msgContent = msgEl.querySelector('.message-content');
            if (msgContent) {
                msgContent.innerHTML = this.renderMarkdown(fullResponse);
                this.highlightCodeBlocks(msgContent);
            }
            // Add message actions (copy, regenerate) for streamed messages
            const wrapper = msgEl.querySelector('.message-content-wrapper');
            if (wrapper && !wrapper.querySelector('.message-actions')) {
                const actions = document.createElement('div');
                actions.className = 'message-actions';
                actions.innerHTML = `
                    <button class="message-action-btn copy-btn" title="复制">
                        <svg viewBox="0 0 24 24" fill="none"><rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/></svg>
                    </button>
                    <button class="message-action-btn regenerate-btn" title="重新生成">
                        <svg viewBox="0 0 24 24" fill="none"><polyline points="1 4 1 10 7 10" stroke="currentColor" stroke-width="2"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" stroke="currentColor" stroke-width="2"/></svg>
                    </button>
                `;
                // Copy handler
                actions.querySelector('.copy-btn').addEventListener('click', async () => {
                    await navigator.clipboard.writeText(fullResponse);
                    const btn = actions.querySelector('.copy-btn');
                    btn.classList.add('copied');
                    btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none"><polyline points="20 6 9 17 4 12" stroke="currentColor" stroke-width="2"/></svg>';
                    this.showToast('已复制到剪贴板 📋', 'success');
                    setTimeout(() => {
                        btn.classList.remove('copied');
                        btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none"><rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/></svg>';
                    }, 2000);
                });
                // Regenerate handler
                actions.querySelector('.regenerate-btn').addEventListener('click', () => {
                    const lastUserMsg = [...this.currentChatHistory].reverse().find(m => m.type === 'user');
                    if (lastUserMsg) {
                        const lastAsstIdx = [...this.currentChatHistory].reverse().findIndex(m => m.type === 'assistant');
                        if (lastAsstIdx !== -1) this.currentChatHistory.splice(this.currentChatHistory.length - 1 - lastAsstIdx, 1);
                        msgEl.remove();
                        this.messageInput.value = lastUserMsg.content;
                        this.sendMessage();
                    }
                });
                wrapper.appendChild(actions);
            }
        }
        if (fullResponse) {
            this.currentChatHistory.push({
                type: 'assistant',
                content: fullResponse,
                timestamp: new Date().toISOString()
            });
            if (this.isCurrentChatFromHistory) {
                this.updateCurrentChatHistory();
                this.renderChatHistory();
            }
        }
    }

    // ==================== AIOps ====================

    async triggerAIOps() {
        if (this.isStreaming) { this.showToast('请等待当前操作完成', 'warning'); return; }

        this.newChat();
        const loadingMsg = this.addLoadingMessage('智能运维分析中...');
        this.currentAIOpsMessage = loadingMsg;

        this.isStreaming = true;
        this.updateUI();

        try {
            await this.sendAIOpsRequest(loadingMsg);
        } catch (error) {
            console.error('AIOps failed:', error);
            if (loadingMsg) {
                const msgContent = loadingMsg.querySelector('.message-content');
                if (msgContent) msgContent.textContent = '智能运维分析失败: ' + error.message;
            }
        } finally {
            this.isStreaming = false;
            this.currentAIOpsMessage = null;
            this.updateUI();
        }
    }

    async sendAIOpsRequest(loadingMsgEl) {
        try {
            const resp = await fetch(`${this.apiBaseUrl}/ai_ops`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });

            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

            let fullResponse = '';
            const reader = resp.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) {
                        if (fullResponse) this.updateAIOpsMessage(loadingMsgEl, fullResponse, []);
                        break;
                    }

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        if (!line.trim() || line.startsWith('id:') || line.startsWith('event:')) continue;

                        if (line.startsWith('data:')) {
                            const rawData = line.substring(5).trim();
                            try {
                                const msg = JSON.parse(rawData);
                                if (msg?.type === 'content') {
                                    fullResponse += msg.data || '';
                                    this.updateAIOpsStreamContent(loadingMsgEl, fullResponse);
                                } else if (msg?.type === 'done') {
                                    this.updateAIOpsMessage(loadingMsgEl, fullResponse, []);
                                    return;
                                } else if (msg?.type === 'error') {
                                    throw new Error(msg.data || 'AI Ops failed');
                                }
                            } catch (e) {
                                if (e.message.includes('AI Ops')) throw e;
                                fullResponse += rawData;
                                this.updateAIOpsStreamContent(loadingMsgEl, fullResponse);
                            }
                        }
                    }
                }
            } finally { reader.releaseLock(); }
        } catch (error) { throw error; }
    }

    updateAIOpsStreamContent(msgEl, content) {
        if (!msgEl) return;
        msgEl.classList.add('aiops-message');
        const msgContent = msgEl.querySelector('.message-content');
        if (msgContent) { msgContent.textContent = content; this.scrollToBottom(); }
    }

    updateAIOpsMessage(msgEl, response, details) {
        if (!msgEl) return this.addAIOpsMessage(response, details);

        msgEl.classList.add('aiops-message');
        const wrapper = msgEl.querySelector('.message-content-wrapper');
        if (!wrapper) return;

        const msgContent = wrapper.querySelector('.message-content');
        if (!msgContent) return;

        msgContent.classList.remove('loading-message-content');
        msgContent.innerHTML = this.renderMarkdown(response);
        this.highlightCodeBlocks(msgContent);

        const spinnerIcon = msgContent.querySelector('.loading-spinner-icon');
        if (spinnerIcon) spinnerIcon.remove();

        if (details?.length > 0) {
            let detailsContainer = msgEl.querySelector('.aiops-details');
            if (!detailsContainer) {
                detailsContainer = document.createElement('div');
                detailsContainer.className = 'aiops-details';
                wrapper.insertBefore(detailsContainer, msgContent);
            }

            detailsContainer.innerHTML = `
                <div class="details-toggle">
                    <svg class="toggle-icon" viewBox="0 0 24 24" fill="none"><path d="M9 18L15 12L9 6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
                    <span>查看详细步骤 (${details.length}条)</span>
                </div>
                <div class="details-content">${details.map((d, i) => `<div class="detail-item"><strong>步骤 ${i+1}:</strong> ${this.escapeHtml(d)}</div>`).join('')}</div>
            `;

            const toggle = detailsContainer.querySelector('.details-toggle');
            const content = detailsContainer.querySelector('.details-content');
            toggle?.addEventListener('click', () => {
                content.classList.toggle('expanded');
                toggle.classList.toggle('expanded');
            });
        }

        this.currentChatHistory.push({
            type: 'assistant',
            content: response,
            timestamp: new Date().toISOString()
        });

        this.scrollToBottom();
        return msgEl;
    }

    addAIOpsMessage(response, details) {
        // Fallback: create a new message
        return this.addMessage('assistant', response, false, true);
    }

    // ==================== Markdown ====================

    initMarkdown() {
        const checkMarked = () => {
            if (typeof marked !== 'undefined') {
                try {
                    marked.setOptions({
                        breaks: true,
                        gfm: true,
                        headerIds: false,
                        mangle: false,
                        highlight: function(code, lang) {
                            if (typeof hljs !== 'undefined' && lang && hljs.getLanguage(lang)) {
                                try { return hljs.highlight(code, { language: lang }).value; }
                                catch {}
                            }
                            return code;
                        }
                    });
                } catch {}
            } else {
                setTimeout(checkMarked, 100);
            }
        };
        checkMarked();
    }

    renderMarkdown(content) {
        if (!content) return '';
        if (typeof marked === 'undefined') return this.escapeHtml(content);
        try { return marked.parse(content); }
        catch { return this.escapeHtml(content); }
    }

    highlightCodeBlocks(container) {
        if (typeof hljs !== 'undefined' && container) {
            try {
                container.querySelectorAll('pre code').forEach(block => {
                    if (!block.classList.contains('hljs')) hljs.highlightElement(block);
                });
            } catch {}
        }
    }

    // ==================== File Upload ====================

    handleFileSelect(event) {
        const file = event.target.files[0];
        if (!file) return;
        if (!this.validateFileType(file)) {
            this.showToast('仅支持 TXT / Markdown (.md) 文件', 'error');
            this.fileInput.value = '';
            return;
        }
        this.uploadFile(file);
    }

    validateFileType(file) {
        const name = file.name.toLowerCase();
        return ['.txt', '.md', '.markdown'].some(ext => name.endsWith(ext));
    }

    async uploadFile(file) {
        if (!this.validateFileType(file)) { this.showToast('文件格式不支持', 'error'); return; }
        if (file.size > 50 * 1024 * 1024) { this.showToast('文件大小不能超过50MB', 'error'); return; }

        this.isStreaming = true;
        this.updateUI();
        this.showUploadOverlay(true, file.name);

        try {
            const formData = new FormData();
            formData.append('file', file);
            const resp = await fetch(`${this.apiBaseUrl}/upload`, { method: 'POST', body: formData });
            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            const data = await resp.json();
            if ((data.code === 200 || data.message === 'success') && data.data) {
                this.addMessage('assistant', `✅ **${file.name}** 已成功上传到知识库`);
                this.showToast('文件上传成功 ✅', 'success');
            } else {
                throw new Error(data.message || '上传失败');
            }
        } catch (error) {
            console.error('Upload failed:', error);
            this.showToast('上传失败: ' + error.message, 'error');
        } finally {
            this.fileInput && (this.fileInput.value = '');
            this.isStreaming = false;
            this.showUploadOverlay(false);
            this.updateUI();
        }
    }

    showUploadOverlay(show, fileName = '') {
        if (!this.loadingOverlay) return;
        if (show) {
            this.loadingOverlay.style.display = 'flex';
            const text = this.loadingOverlay.querySelector('.loading-text');
            const subtext = this.loadingOverlay.querySelector('.loading-subtext');
            if (text) text.textContent = '正在上传文件...';
            if (subtext) subtext.textContent = fileName ? `上传: ${fileName}` : '请稍候';
            document.body.style.overflow = 'hidden';
        } else {
            this.loadingOverlay.style.display = 'none';
            document.body.style.overflow = '';
        }
    }

    // ==================== UI Helpers ====================

    updateUI() {
        if (this.currentModeText) {
            this.currentModeText.textContent = this.currentMode === 'quick' ? '快速' : '流式';
        }

        document.querySelectorAll('.dropdown-item').forEach(item => {
            item.classList.toggle('active', item.dataset.mode === this.currentMode);
        });

        if (this.sendButton) this.sendButton.disabled = this.isStreaming;
        if (this.messageInput) this.messageInput.disabled = this.isStreaming;
    }

    selectMode(mode) {
        if (this.isStreaming) { this.showToast('请等待当前操作完成', 'warning'); return; }
        this.currentMode = mode;
        this.updateUI();
        const names = { quick: '快速', stream: '流式' };
        this.showToast(`已切换到${names[mode]}模式`, 'info');
    }

    toggleModeDropdown() {
        this.modeSelectorBtn?.closest('.mode-selector-wrapper')?.classList.toggle('active');
    }

    closeModeDropdown() {
        this.modeSelectorBtn?.closest('.mode-selector-wrapper')?.classList.remove('active');
    }

    toggleToolsMenu() {
        this.toolsBtn?.closest('.tools-btn-wrapper')?.classList.toggle('active');
    }

    closeToolsMenu() {
        this.toolsBtn?.closest('.tools-btn-wrapper')?.classList.remove('active');
    }

    scrollToBottom() {
        if (this.chatMessages) {
            this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        }
    }

    generateSessionId() {
        return 'sess_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    formatRelativeTime(date) {
        const now = new Date();
        const diff = now - date;
        const mins = Math.floor(diff / 60000);
        if (mins < 1) return '刚刚';
        if (mins < 60) return `${mins}分钟前`;
        const hours = Math.floor(mins / 60);
        if (hours < 24) return `${hours}小时前`;
        const days = Math.floor(hours / 24);
        if (days < 7) return `${days}天前`;
        return date.toLocaleDateString('zh-CN');
    }

    // ==================== Search Popover ====================

    openSearchPopover() {
        this.searchOverlay?.classList.add('active');
        this.searchPopoverInput?.focus();
        // Reset state
        if (this.searchPopoverInput) this.searchPopoverInput.value = '';
        if (this.searchPopoverResults) this.searchPopoverResults.innerHTML = '<div class="search-popover-empty">输入关键词开始搜索知识库</div>';
    }

    closeSearchPopover() {
        this.searchOverlay?.classList.remove('active');
    }

    async executeSearch() {
        const query = this.searchPopoverInput?.value.trim();
        if (!query || query.length < 1) return;

        // Show loading
        if (this.searchPopoverResults) {
            this.searchPopoverResults.innerHTML = '<div class="search-popover-loading">搜索中...</div>';
        }

        try {
            const resp = await fetch(`${this.apiBaseUrl}/search-docs?q=${encodeURIComponent(query)}`);
            const data = await resp.json();

            if (!this.searchPopoverResults) return;

            if (data.code !== 200 || !data.data) {
                this.searchPopoverResults.innerHTML = `<div class="search-popover-empty">搜索失败：${data.message || '未知错误'}</div>`;
                return;
            }

            const resultData = data.data;

            // Check for error
            if (resultData.success === false) {
                this.searchPopoverResults.innerHTML = `
                    <div class="search-popover-empty">
                        <div style="font-size:40px;margin-bottom:8px;">⚠️</div>
                        <div style="font-weight:600;margin-bottom:4px;">搜索不可用</div>
                        <div style="font-size:12px;">${this.escapeHtml(resultData.hint || resultData.error || 'Milvus 向量数据库未连接')}</div>
                        <div style="font-size:11px;margin-top:8px;color:#aaa;">请确保 Milvus 已在 localhost:19530 启动</div>
                    </div>`;
                return;
            }

            // Check for no results
            if (resultData.hint && resultData.hint.includes('未找到')) {
                this.searchPopoverResults.innerHTML = `
                    <div class="search-popover-empty">
                        <div style="font-size:40px;margin-bottom:8px;">🔍</div>
                        <div>未找到与 "${this.escapeHtml(query)}" 相关的文档</div>
                        <div style="font-size:12px;margin-top:4px;">请尝试其他关键词，或先通过"上传文件"功能导入文档</div>
                    </div>`;
                return;
            }

            // Try to parse results
            let results = [];
            const rawResult = resultData.rawResult;
            if (rawResult) {
                try {
                    const parsed = typeof rawResult === 'string' ? JSON.parse(rawResult) : rawResult;
                    if (Array.isArray(parsed)) {
                        results = parsed;
                    } else if (parsed.results) {
                        results = parsed.results;
                    }
                } catch {}
            }

            if (results.length === 0) {
                this.searchPopoverResults.innerHTML = `
                    <div class="search-popover-empty">未找到相关文档，请尝试其他关键词</div>`;
                return;
            }

            this.searchPopoverResults.innerHTML = results.map((r, i) => `
                <div class="search-result-item" data-index="${i}">
                    <div class="result-title">📄 ${this.escapeHtml(r.id || `文档 ${i+1}`)}</div>
                    <div class="result-snippet">${this.escapeHtml((r.content || '').substring(0, 200))}</div>
                    ${r.score !== undefined ? `<div class="result-score">相关度: ${(r.score * 100).toFixed(0)}%</div>` : ''}
                </div>
            `).join('');

            // Click result to send as chat prompt
            this.searchPopoverResults.querySelectorAll('.search-result-item').forEach(item => {
                item.addEventListener('click', () => {
                    const result = results[parseInt(item.dataset.index)];
                    const content = result?.content || '';
                    this.closeSearchPopover();
                    this.messageInput.value = `请基于以下文档内容回答问题：\n\n${content.substring(0, 500)}`;
                    this.messageInput.focus();
                });
            });

        } catch (e) {
            console.error('Search docs failed:', e);
            if (this.searchPopoverResults) {
                this.searchPopoverResults.innerHTML = `
                    <div class="search-popover-empty">
                        <div style="font-size:40px;margin-bottom:8px;">⚠️</div>
                        <div>搜索失败</div>
                        <div style="font-size:12px;">${this.escapeHtml(e.message)}</div>
                        <div style="font-size:11px;margin-top:8px;color:#aaa;">请检查后端服务是否正常运行</div>
                    </div>`;
            }
        }
    }

    // ==================== Toast Notifications ====================

    showToast(message, type = 'info') {
        if (!this.toastContainer) return;
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = message;
        this.toastContainer.appendChild(toast);

        setTimeout(() => {
            toast.classList.add('removing');
            setTimeout(() => toast.parentNode && toast.parentNode.removeChild(toast), 300);
        }, 3000);
    }
}

// ==================== Bootstrap ====================

document.addEventListener('DOMContentLoaded', () => {
    window.app = new SuperBizAgentApp();
});
