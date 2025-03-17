package com.beyond.StomachForce.Post.service;

import com.beyond.StomachForce.Post.domain.Comment;
import com.beyond.StomachForce.Post.domain.CommentDeleteReq;
import com.beyond.StomachForce.Post.domain.Enum.PostStatus;
import com.beyond.StomachForce.Post.dtos.*;
import com.beyond.StomachForce.Post.repository.CommentRepository;
import com.beyond.StomachForce.User.domain.User;
import com.beyond.StomachForce.Post.domain.Post;
import com.beyond.StomachForce.Post.repository.PostRepository;
import com.beyond.StomachForce.User.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class PostService {
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final LikeService likeService;
    private final LikeRabbitmqService likeRabbitmqService;
    private final CommentRepository commentRepository;
    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    public PostService(PostRepository postRepository, UserRepository userRepository, LikeService likeService, LikeRabbitmqService likeRabbitmqService, CommentRepository commentRepository, S3Client s3Client) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.likeService = likeService;
        this.likeRabbitmqService = likeRabbitmqService;
        this.commentRepository = commentRepository;
        this.s3Client = s3Client;
    }
    public Post save(PostCreateReq postCreateReq) throws IOException {
        String identify = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByIdentify(identify).orElseThrow(()->new EntityNotFoundException("없는 회원입니다."));
        String contents = postCreateReq.getContents();
        Post tempPost = postCreateReq.toEntity(user);
        Post post = postRepository.save(tempPost);
        List<MultipartFile> images = postCreateReq.getPostPhotos();
        for(MultipartFile image : images){
            try {
                String fileName = user.getId() + "_" + image.getOriginalFilename();
                
                // ✅ 바로 S3에 업로드 (로컬 파일 저장 없이)
                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(fileName)
                        .contentType(image.getContentType()) // ✅ 파일 타입 설정
                        .build();
    
                // ✅ S3에 메모리에서 바로 업로드 (RequestBody.fromBytes 사용)
                s3Client.putObject(putObjectRequest, RequestBody.fromBytes(image.getBytes()));
    
                // ✅ S3에서 URL 가져오기
                String s3Url = s3Client.utilities().getUrl(a -> a.bucket(bucket).key(fileName)).toExternalForm();
                if (s3Url == null || s3Url.isEmpty()) {
                    throw new RuntimeException("🚨 S3 URL 가져오기 실패: " + fileName);
                }
    
                post.updatePostImagePath(s3Url);
            } catch (IOException e) {
                throw new RuntimeException("🚨 이미지 업로드 중 오류 발생: " + e.getMessage(), e);
            }
        }
        return post;
    }

    public void updateByIdentify(PostUpdateReq postUpdateReq){
        Post post = postRepository.findById(postUpdateReq.getId()).orElseThrow(()->new EntityNotFoundException("없는 게시글입니다"));
        post.updatePost(postUpdateReq);
    }

    public void delete(Long postId){
        Post post = postRepository.findById(postId).orElseThrow(()->new EntityNotFoundException("없는 게시글입니다."));
        post.deletePost();
    }

    public LikeResDto getLikes(Long postId){
        String identify = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByIdentify(identify).orElseThrow(()->new EntityNotFoundException("없는 회원입니다."));
        Long userId = user.getId();
        Long Like = likeService.getLikeCount(postId);
        boolean isLiked = likeService.isUserLikedPost(postId, userId);
        return LikeResDto.builder()
                .postId(postId)
                .likes(Like)
                .isLiked(isLiked)
                .build();
    }

    public void postLikes(Long postId){
        String identify = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByIdentify(identify).orElseThrow(()->new EntityNotFoundException("없는 회원입니다."));
        Long userId = user.getId();
        likeService.toggleLike(postId, String.valueOf(userId));
        Long updateLike = likeService.getLikeCount(postId);
        boolean isLiked = likeService.isUserLikedPost(postId, userId);
        LikeRabbitDto likeRabbitDto = LikeRabbitDto.builder().postId(postId).likes(updateLike).build();
        likeRabbitmqService.publish(likeRabbitDto);
    }

    public Comment comments(Long postId,CommentCreateDto commentCreateDto){
        String identify = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByIdentify(identify).orElseThrow(()->new EntityNotFoundException("없는 회원입니다."));
        Post post = postRepository.findById(postId).orElseThrow(()->new EntityNotFoundException("없는 게시글입니다."));
        String contents = commentCreateDto.getContents();
        Comment comment = Comment.builder().contents(contents).post(post).userNickname(user.getNickName()).userProfile(user.getProfilePhoto()).build();
        return commentRepository.save(comment);
    }

    public List<CommentListRes> getComments(Long postId){
        List<Comment> comments = commentRepository.findByPostId(postId);

        return comments.stream()
                .map(c -> CommentListRes.builder()
                        .id(c.getId())
                        .contents(c.getContents())
                        .userNickname(c.getUserNickname())
                        .userProfile(c.getUserProfile())
                        .updatedTime(c.getUpdatedTime())
                        .build())
                .collect(Collectors.toList());
    }

    public Comment updateComment(CommentUpdateDto commentUpdateDto){
        Comment comment = commentRepository.findById(commentUpdateDto.getCommentId()).orElseThrow(()->new EntityNotFoundException("해당 댓글이 없습니다."));
        comment.update(commentUpdateDto.getContents());
        return comment;
    }

    public String deleteComment(CommentDeleteReq commentDeleteReq){
        commentRepository.deleteById(commentDeleteReq.getCommentId());
        return "댓글이 삭제되었습니다.";
    }

    public PostDetailRes postDetail(Long postId){
        Post post = postRepository.findById(postId).orElseThrow(()->new EntityNotFoundException("없는 게시글입니다."));
        LocalDateTime createdtime = post.getCreatedTime();
        PostDetailRes postDetailRes = post.postDetails(likeService.getLikeCount(postId),createdtime);
        return postDetailRes;
    }

    public Page<PostDetailRes> findAll(Pageable pageable){
        Pageable sortedPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id")
        );

        Page<Post> posts = postRepository.findByPostStatus(PostStatus.Y,sortedPageable);
        return posts.map(post -> PostDetailRes.builder()
                        .postId(post.getId())
                        .contents(post.getContents())
                        .likes(likeService.getLikeCount(post.getId()))
                        .tags(post.getTags())
                        .postPhotos(post.getPostPhotos())
                        .userNickName(post.getUser().getNickName())
                        .userProfile(post.getUser().getProfilePhoto())
                        .createdTime(post.getCreatedTime())
                        .build());
    }

    public FindWriterDto findWriter(Long postId){
        Post post = postRepository.findById(postId).orElseThrow(()->new EntityNotFoundException("해당 게시글 없습니다."));
        User user = post.getUser();
        FindWriterDto findWriterDto = FindWriterDto.builder()
                .userNickName(user.getNickName())
                .userProfile(user.getProfilePhoto())
                .build();
        return findWriterDto;
    }

}
