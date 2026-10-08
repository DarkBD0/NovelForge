package com.novelforge.generation;

import com.novelforge.novel.Novel;
import com.novelforge.novel.Novel.Kind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Deterministically samples confirmed long-range chapters outside the ordinary three-chapter prose window. */
@Component
public class HistoricalChapterSelector {
    public List<Integer> select(Novel novel,int currentChapter,int maxChapters) {
        if(novel==null || currentChapter<=4 || maxChapters<=0) return List.of();
        List<Integer> eligible=novel.artifacts.stream()
                .filter(item->item.kind==Kind.CHAPTER&&item.clean()&&item.chapterNumber<currentChapter-3)
                .map(item->item.chapterNumber).distinct().sorted(Comparator.naturalOrder()).toList();
        if(eligible.size()<=maxChapters) return eligible;
        LinkedHashSet<Integer> selected=new LinkedHashSet<>();
        if(maxChapters==1) selected.add(eligible.getFirst());
        else for(int i=0;i<maxChapters;i++) {
            int index=(int)Math.round((double)i*(eligible.size()-1)/(maxChapters-1));
            selected.add(eligible.get(index));
        }
        return new ArrayList<>(selected);
    }
}
