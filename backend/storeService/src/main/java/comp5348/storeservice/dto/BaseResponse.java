package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BaseResponse {
    private Result result;

    @Data
    @NoArgsConstructor
    public static class Result {
        private String message;
        private String responseCode;

        public Result(String message, String responseCode) {
            this.message = message;
            this.responseCode = responseCode;
        }
    }

    public BaseResponse(String message, String responseCode) {
        this.result = new Result(message, responseCode);
    }
}

