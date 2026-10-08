package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Artifact;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable identity for content review, deliberately excluding internal extracted state. */
@Component
public class ContentReviewFingerprint {
    public String of(Novel novel,Artifact target,ModelGateway.Generated candidate,String instructions) {
        StringBuilder value=new StringBuilder(ReviewPolicy.VERSION).append('\n')
                .append(text(novel.title)).append('\n').append(text(novel.synopsis)).append('\n')
                .append(text(novel.requirements)).append('\n').append(novel.targetWords).append('\n')
                .append(novel.approvedMaxWords).append('\n').append(target==null?"":target.kind).append('\n')
                .append(target==null?0:target.chapterNumber).append('\n').append(text(instructions)).append('\n')
                .append(text(candidate.title())).append('\n').append(text(candidate.content())).append('\n')
                .append(text(candidate.summary())).append('\n');
        for(Artifact artifact:novel.artifacts) {
            if(artifact==target) continue;
            value.append(artifact.kind).append(':').append(artifact.chapterNumber).append(':')
                    .append(artifact.batchNumber).append(':').append(text(artifact.approvedVersionId)).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String text(String value) { return value==null?"":value; }
}
