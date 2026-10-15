package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RunbookTextProcessorTest {
    @Test void codeCommentsDoNotCreateDocumentSections() {
        var chunks = RunbookTextProcessor.chunk("# 诊断\n```sh\n# shell 注释\n  echo safe\n```\n完成\n# 验证\n检查恢复");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isEqualTo("诊断");
        assertThat(chunks.get(0).content()).isEqualTo("```sh\n# shell 注释\n  echo safe\n```\n完成");
        assertThat(chunks.get(1).heading()).isEqualTo("验证");
    }
    @Test void tildeFencesNeedSameKindAndSufficientLengthToClose() {
        var chunks = RunbookTextProcessor.chunk("# 诊断\n~~~~sql\n# 注释\n~~~\n```\n# 仍是代码\n~~~~~\n# 验证\n检查");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).contains("# 注释", "# 仍是代码", "~~~~~");
        assertThat(chunks.get(1).heading()).isEqualTo("验证");
    }
    @Test void unfinishedFencesDoNotPromoteCommentsToHeadings() {
        var chunks = RunbookTextProcessor.chunk("# 诊断\n```sh\n# 片段仍未闭合\necho safe");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("# 片段仍未闭合");
    }
    @Test void invalidBacktickInfoDoesNotOpenFence() {
        var chunks = RunbookTextProcessor.chunk("# 诊断\n```bad`info\n# 验证\n检查");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).heading()).isEqualTo("验证");
    }
    @Test void keepsExistingCrLfNormalizationAndStableChunkIndices() {
        var chunks = RunbookTextProcessor.chunk("# 诊断\r\n  ```sh\r\n# 注释\r\n  ```\r\n# 验证\r\n检查");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("```sh\n# 注释\n  ```");
        assertThat(chunks).extracting(RunbookTextProcessor.ChunkDraft::index).containsExactly(0, 1);
    }
}
