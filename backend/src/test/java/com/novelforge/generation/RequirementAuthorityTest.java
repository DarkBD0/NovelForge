package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.outline.OutlineSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementAuthorityTest {
    private final RequirementAuthority authority=new RequirementAuthority();

    @Test void usesProjectInputBeforeAnOutlineIsConfirmed() {
        Novel novel=new Novel(); novel.requirements="解释重生原因";
        RequirementAuthority.Snapshot result=authority.resolve(novel);
        assertThat(result.authority()).isEqualTo("PROJECT_INPUT");
        assertThat(result.text()).isEqualTo("解释重生原因");
    }

    @Test void confirmedOutlineReplacesOlderProjectRequirementsForModelCalls() {
        Novel novel=new Novel(); novel.requirements="解释重生原因";
        Novel.Artifact outline=new Novel.Artifact(); outline.kind=Novel.Kind.OUTLINE;
        Novel.Version oldVersion=version("old","解释重生原因");
        Novel.Version currentVersion=version("current","不解释重生原因");
        outline.versions.add(oldVersion); outline.versions.add(currentVersion); outline.approvedVersionId=currentVersion.id;
        novel.artifacts.add(outline);

        RequirementAuthority.Snapshot result=authority.resolve(novel);

        assertThat(result.authority()).isEqualTo("APPROVED_OUTLINE_SNAPSHOT");
        assertThat(result.sourceVersionId()).isEqualTo(currentVersion.id);
        assertThat(result.text()).contains("不解释重生原因").doesNotContain("- 解释重生原因");
    }

    @Test void assumptionsNeverBecomeAuthoritativeRequirements() {
        Novel novel=new Novel(); novel.requirements="初始要求";
        Novel.Artifact outline=new Novel.Artifact(); outline.kind=Novel.Kind.OUTLINE;
        Novel.Version current=version("current","保留爽文节奏");
        OutlineSpec.RequirementTrace assumption=new OutlineSpec.RequirementTrace();
        assumption.id="assumption_1"; assumption.text="模型猜测"; assumption.source="agent"; assumption.type="ASSUMPTION";
        current.outlineSpec.requirements=List.of(current.outlineSpec.requirements.getFirst(),assumption);
        outline.versions.add(current); outline.approvedVersionId=current.id; novel.artifacts.add(outline);

        assertThat(authority.resolve(novel).text()).contains("保留爽文节奏").doesNotContain("模型猜测");
    }

    private Novel.Version version(String id,String requirement) {
        Novel.Version version=new Novel.Version(); version.id=id; version.outlineSpec=new OutlineSpec();
        OutlineSpec.RequirementTrace trace=new OutlineSpec.RequirementTrace();
        trace.id="req_1"; trace.text=requirement; trace.source="requirements"; trace.type="EXPLICIT";
        version.outlineSpec.requirements=List.of(trace); return version;
    }
}
