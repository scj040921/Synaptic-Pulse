package cn.edu.gzhu.pulse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final JdbcTemplate db; private final AuthService auth; private final UserService users; private final CampusMapService maps;
    public EventController(JdbcTemplate db, AuthService auth, UserService users, CampusMapService maps) { this.db=db; this.auth=auth; this.users=users; this.maps=maps; }

    @GetMapping
    public List<Event> list(@RequestParam(defaultValue="") String q, HttpServletRequest request) {
        long ownId=auth.requireUser(request);
        if(q.length()>80) throw new ApiException(HttpStatus.BAD_REQUEST,"关键词最多80字");
        String keyword="%"+q.strip().toLowerCase(java.util.Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return db.query("SELECT e.id FROM campus_events e JOIN users u ON u.id=e.host_id WHERE u.campus=? " +
                "AND e.host_id NOT IN (SELECT blocked_id FROM blocks WHERE blocker_id=?) " +
                "AND e.host_id NOT IN (SELECT blocker_id FROM blocks WHERE blocked_id=?) " +
                "AND (LOWER(e.title) LIKE ? ESCAPE '!' OR LOWER(e.content) LIKE ? ESCAPE '!') ORDER BY e.starts_at DESC LIMIT 50",
                (rs,row)->find(rs.getLong(1),ownId),users.get(ownId).campus(),ownId,ownId,keyword,keyword);
    }
    @GetMapping("/{id}")
    public Event detail(@PathVariable long id, HttpServletRequest request) { return find(id, auth.requireUser(request)); }

    @PostMapping
    public Event create(@Valid @RequestBody NewEvent input, HttpServletRequest request) {
        long ownId=auth.requireUser(request);
        if(input.startsAt().isBefore(LocalDateTime.now())) throw new ApiException(HttpStatus.BAD_REQUEST,"活动时间须晚于当前时间");
        String placeId = input.placeId() == null || input.placeId().isBlank() ? null : input.placeId();
        CampusMapService.validatePosition(input.latitude(), input.longitude(), null);
        var place = placeId == null ? null : maps.requirePlace(ownId, users.get(ownId).campus(), placeId);
        String locationName = place == null ? input.locationName().strip() : place.name();
        Double latitude = place == null ? input.latitude() : place.latitude();
        Double longitude = place == null ? input.longitude() : place.longitude();
        var keys=new GeneratedKeyHolder();
        db.update(connection->{var ps=connection.prepareStatement("INSERT INTO campus_events(host_id,title,content,location_name,starts_at,capacity,place_id,latitude,longitude) VALUES(?,?,?,?,?,?,?,?,?)",new String[]{"ID"});
            ps.setLong(1,ownId);ps.setString(2,input.title().strip());ps.setString(3,input.content().strip());ps.setString(4,locationName);ps.setTimestamp(5,Timestamp.valueOf(input.startsAt()));ps.setInt(6,input.capacity());ps.setString(7,placeId);ps.setObject(8,latitude);ps.setObject(9,longitude);return ps;},keys);
        return find(keys.getKey().longValue(),ownId);
    }
    @PutMapping("/{id}/signup")
    @Transactional
    public Event signup(@PathVariable long id,HttpServletRequest request) {
        long ownId=auth.requireUser(request);
        // Serialize signups on the event row so concurrent requests cannot overbook.
        var rows=db.query("SELECT id FROM campus_events WHERE id=? FOR UPDATE",(rs,row)->rs.getLong(1),id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"活动不存在");
        Event event=find(id,ownId);
        checkVisible(event,ownId);
        if(event.joined()) return event;
        if(event.startsAt().isBefore(LocalDateTime.now())) throw new ApiException(HttpStatus.BAD_REQUEST,"活动已开始，报名已截止");
        if(event.participants()>=event.capacity()) throw new ApiException(HttpStatus.CONFLICT,"活动名额已满");
        db.update("INSERT INTO event_signups(event_id,user_id) VALUES(?,?)",id,ownId);
        return find(id,ownId);
    }
    @DeleteMapping("/{id}/signup")
    public Event cancel(@PathVariable long id,HttpServletRequest request) {long ownId=auth.requireUser(request);db.update("DELETE FROM event_signups WHERE event_id=? AND user_id=?",id,ownId);return find(id,ownId);}
    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id,HttpServletRequest request) {if(db.update("DELETE FROM campus_events WHERE id=? AND host_id=?",id,auth.requireUser(request))!=1)throw new ApiException(HttpStatus.FORBIDDEN,"只能删除自己发布的活动");}
    private Event find(long id,long ownId) {
        var rows=db.query("SELECT e.*,u.display_name,COALESCE(e.latitude,p.latitude) AS map_latitude,COALESCE(e.longitude,p.longitude) AS map_longitude FROM campus_events e JOIN users u ON u.id=e.host_id LEFT JOIN campus_places p ON p.id=e.place_id WHERE e.id=?",(rs,row)->new Event(rs.getLong("id"),rs.getLong("host_id"),rs.getString("display_name"),rs.getString("title"),rs.getString("content"),rs.getString("location_name"),rs.getTimestamp("starts_at").toLocalDateTime(),rs.getInt("capacity"),0,false,rs.getString("place_id"),(Double)rs.getObject("map_latitude"),(Double)rs.getObject("map_longitude")),id);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"活动不存在");
        Event e=rows.get(0); checkVisible(e,ownId);
        int total=db.queryForObject("SELECT COUNT(*) FROM event_signups WHERE event_id=?",Integer.class,id);
        int joined=db.queryForObject("SELECT COUNT(*) FROM event_signups WHERE event_id=? AND user_id=?",Integer.class,id,ownId);
        return new Event(e.id(),e.hostId(),e.hostName(),e.title(),e.content(),e.locationName(),e.startsAt(),e.capacity(),total,joined>0,e.placeId(),e.latitude(),e.longitude());
    }
    private void checkVisible(Event e,long ownId) {
        if(!users.get(ownId).campus().equals(users.get(e.hostId()).campus()))throw new ApiException(HttpStatus.FORBIDDEN,"仅支持查看同校活动");
        int blocked=db.queryForObject("SELECT COUNT(*) FROM blocks WHERE (blocker_id=? AND blocked_id=?) OR (blocker_id=? AND blocked_id=?)",Integer.class,ownId,e.hostId(),e.hostId(),ownId);
        if(blocked>0)throw new ApiException(HttpStatus.FORBIDDEN,"无法查看该活动");
    }
    public record NewEvent(@NotBlank @Size(max=80) String title,@NotBlank @Size(max=2000) String content,
                           @NotBlank @Size(max=80) String locationName,@NotNull LocalDateTime startsAt,@Min(1) @Max(300) int capacity,@Size(max=40) String placeId, Double latitude, Double longitude) { }
    public record Event(long id,long hostId,String hostName,String title,String content,String locationName,LocalDateTime startsAt,int capacity,int participants,boolean joined,String placeId,Double latitude,Double longitude) { }
}
